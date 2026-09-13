package io.kubastion.terminal;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import io.kubastion.config.KubastionProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Una sola sessione di terminale, aperta su una shell locale.
 *
 * Il login lo fai tu dentro il terminale: chiave, ssh, menu del gateway,
 * destinazione. kubastion non sa nulla di tutto questo e non ci si intromette —
 * e' esattamente il motivo per cui funziona con qualunque gateway.
 *
 * Quando serve, l'applicazione inietta un comando nella stessa sessione e ne
 * cattura l'output fra due marcatori, nascondendolo al terminale: altrimenti
 * ogni tre secondi ti troveresti addosso una pagina di JSON.
 */
@Service
public class TerminalService {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    private final KubastionProperties props;
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "terminal-timeouts");
        t.setDaemon(true);
        return t;
    });

    private PtyProcess process;
    private Writer toTerminal;
    private volatile Capture capture;

    public TerminalService(KubastionProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------ ciclo vita

    public synchronized void ensureStarted() {
        if (isAlive()) {
            return;
        }
        try {
            Map<String, String> env = new HashMap<>(System.getenv());
            // senza TERM molti programmi interattivi si comportano da "dumb"
            env.putIfAbsent("TERM", "xterm-256color");

            process = new PtyProcessBuilder()
                    .setCommand(shellCommand())
                    .setEnvironment(env)
                    .setInitialColumns(120)
                    .setInitialRows(30)
                    .start();
            toTerminal = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);

            Thread reader = new Thread(this::pump, "terminal-reader");
            reader.setDaemon(true);
            reader.start();
            log.info("Terminale avviato: {}", String.join(" ", shellCommand()));
        } catch (IOException e) {
            throw new IllegalStateException("Impossibile avviare il terminale: " + e.getMessage(), e);
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @PreDestroy
    public synchronized void stop() {
        Capture pending = capture;
        if (pending != null) {
            pending.future().completeExceptionally(new IllegalStateException("Terminale chiuso"));
            capture = null;
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        scheduler.shutdownNow();
    }

    private String[] shellCommand() {
        String configured = props.terminal().command();
        if (configured != null && !configured.isBlank()) {
            List<String> args = props.terminal().args();
            String[] command = new String[1 + (args == null ? 0 : args.size())];
            command[0] = configured;
            if (args != null) {
                for (int i = 0; i < args.size(); i++) {
                    command[i + 1] = args.get(i);
                }
            }
            return command;
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new String[]{"powershell.exe", "-NoLogo"};
        }
        String shell = System.getenv("SHELL");
        return new String[]{shell == null || shell.isBlank() ? "/bin/bash" : shell, "-l"};
    }

    // ------------------------------------------------------------------- I/O

    /** Quello che digiti nel browser finisce qui, tale e quale. */
    public void write(String data) {
        Writer writer = toTerminal;
        if (writer == null) {
            return;
        }
        try {
            synchronized (this) {
                writer.write(data);
                writer.flush();
            }
        } catch (IOException e) {
            log.debug("scrittura sul terminale fallita: {}", e.toString());
        }
    }

    public void resize(int columns, int rows) {
        PtyProcess current = process;
        if (current != null && columns > 0 && rows > 0) {
            current.setWinSize(new WinSize(columns, rows));
        }
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    /** Legge l'output del PTY e lo inoltra, salvo quando stiamo catturando. */
    private void pump() {
        char[] buffer = new char[8192];
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                onOutput(new String(buffer, 0, read));
            }
        } catch (IOException e) {
            log.debug("lettura terminale terminata: {}", e.toString());
        } finally {
            emit("\r\n[kubastion] sessione terminata.\r\n");
        }
    }

    private void onOutput(String chunk) {
        Capture current = capture;
        if (current == null) {
            emit(chunk);
            return;
        }
        current.buffer().append(chunk);
        int end = current.buffer().indexOf(current.endMarker());
        if (end < 0) {
            return;
        }
        int start = current.buffer().indexOf(current.startMarker());
        String payload = (start >= 0 && start + current.startMarker().length() <= end)
                ? current.buffer().substring(start + current.startMarker().length(), end)
                : "";
        finishCapture(current, () -> current.future().complete(payload));
    }

    private void emit(String text) {
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(text);
            } catch (Exception e) {
                log.debug("listener terminale in errore: {}", e.toString());
            }
        }
    }

    // --------------------------------------------------------------- cattura

    /**
     * Esegue un comando nella sessione e ne restituisce il solo output, senza
     * mostrarlo nel terminale. Se una cattura e' gia' in corso rifiuta subito:
     * meglio saltare un giro di polling che sovrapporre due comandi.
     */
    public CompletableFuture<String> runCaptured(String command) {
        if (!isAlive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Terminale non attivo"));
        }
        synchronized (this) {
            if (capture != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Comando gia' in corso"));
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            String startMarker = "__KB" + id + "S__";
            String endMarker = "__KB" + id + "E__";
            CompletableFuture<String> future = new CompletableFuture<>();

            // Il PTY fa l'eco della riga che scriviamo. Se il comando contenesse
            // i marcatori per esteso, li ritroveremmo nell'eco e chiuderemmo la
            // cattura sul comando invece che sul suo output. Spezzandoli in due
            // stringhe adiacenti, l'eco mostra `"__KBxxx""S__"` mentre echo
            // stampa `__KBxxxS__`: solo l'output contiene il marcatore vero.
            String echoStart = "echo \"__KB" + id + "\"\"S__\"";
            String echoEnd = "echo \"__KB" + id + "\"\"E__\"";

            ScheduledFuture<?> timeout = scheduler.schedule(
                    () -> abortCapture(id),
                    Math.max(2, props.monitor().timeoutSeconds()), TimeUnit.SECONDS);

            capture = new Capture(id, startMarker, endMarker, new StringBuilder(), future, timeout);

            // stderr confluisce in stdout: se kubectl fallisce vogliamo leggerne il motivo
            write(echoStart + "; " + command + " 2>&1; " + echoEnd + "\n");
            return future;
        }
    }

    private void abortCapture(String id) {
        Capture current = capture;
        if (current == null || !current.id().equals(id)) {
            return;
        }
        finishCapture(current, () -> current.future().completeExceptionally(
                new IllegalStateException("Nessuna risposta dal terminale entro il timeout")));
    }

    /** Chiude la cattura e riapre il flusso verso il terminale, sempre. */
    private void finishCapture(Capture current, Runnable completion) {
        synchronized (this) {
            if (capture == current) {
                capture = null;
            }
        }
        current.timeout().cancel(false);
        completion.run();
    }

    private record Capture(
            String id,
            String startMarker,
            String endMarker,
            StringBuilder buffer,
            CompletableFuture<String> future,
            ScheduledFuture<?> timeout) {
    }
}
