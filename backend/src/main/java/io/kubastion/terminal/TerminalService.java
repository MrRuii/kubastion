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
 * A single terminal session, attached to a local shell.
 *
 * You do the login yourself inside it: key, ssh, gateway menu, destination.
 * kubastion knows nothing about any of that and never interferes — which is
 * exactly why it works with gateways it has never seen.
 *
 * When asked, it injects a command into that same session and captures the
 * output between two markers, hiding it from the terminal: otherwise you would
 * get a page of JSON thrown at you every few seconds.
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

    // ------------------------------------------------------------- lifecycle

    public synchronized void ensureStarted() {
        if (isAlive()) {
            return;
        }
        try {
            Map<String, String> env = new HashMap<>(System.getenv());
            // without TERM many interactive programs fall back to "dumb" mode
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
            log.info("Terminal started: {}", String.join(" ", shellCommand()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the terminal: " + e.getMessage(), e);
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @PreDestroy
    public synchronized void stop() {
        Capture pending = capture;
        if (pending != null) {
            pending.future().completeExceptionally(new IllegalStateException("Terminal closed"));
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

    /** Whatever you type in the browser lands here, byte for byte. */
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
            log.debug("terminal write failed: {}", e.toString());
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

    /** Reads PTY output and forwards it, except while a capture is running. */
    private void pump() {
        char[] buffer = new char[8192];
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                onOutput(new String(buffer, 0, read));
            }
        } catch (IOException e) {
            log.debug("terminal read ended: {}", e.toString());
        } finally {
            emit("\r\n[kubastion] session ended.\r\n");
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
                log.debug("terminal listener failed: {}", e.toString());
            }
        }
    }

    // --------------------------------------------------------------- capture

    /**
     * Runs a command in the session and returns only its output, without
     * showing it in the terminal. If a capture is already running it fails
     * immediately: skipping one polling round beats overlapping two commands.
     */
    public CompletableFuture<String> runCaptured(String command) {
        if (!isAlive()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Terminal is not running"));
        }
        synchronized (this) {
            if (capture != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("A command is already running"));
            }
            String id = UUID.randomUUID().toString().substring(0, 8);
            String startMarker = "__KB" + id + "S__";
            String endMarker = "__KB" + id + "E__";
            CompletableFuture<String> future = new CompletableFuture<>();

            // A PTY echoes back the line we write. If the command contained the
            // markers verbatim we would find them in that echo and close the
            // capture on the command instead of its output. Split across two
            // adjacent strings, the echo shows `"__KBxxx""S__"` while echo
            // prints `__KBxxxS__`: only the output holds the real marker.
            String echoStart = "echo \"__KB" + id + "\"\"S__\"";
            String echoEnd = "echo \"__KB" + id + "\"\"E__\"";

            ScheduledFuture<?> timeout = scheduler.schedule(
                    () -> abortCapture(id),
                    Math.max(2, props.monitor().timeoutSeconds()), TimeUnit.SECONDS);

            capture = new Capture(id, startMarker, endMarker, new StringBuilder(), future, timeout);

            // stderr is folded into stdout: when kubectl fails we want the reason
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
                new IllegalStateException("No response from the terminal before the timeout")));
    }

    /** Ends the capture and always hands the stream back to the terminal. */
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
