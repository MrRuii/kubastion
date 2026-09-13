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
 *
 * The session belongs to you, not to the poller. {@link #busyReason(int)} is how
 * that is enforced: while you have a line half-typed, or while anything of yours
 * is still writing to the terminal, nothing is injected at all.
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

    /**
     * A prompt redraw lands a few milliseconds after a capture ends. It is our
     * output, not yours, so it must not read as terminal activity.
     */
    private static final long PROMPT_SETTLE_MS = 1000;

    /**
     * And it must not be shown either. Every injected command makes the shell
     * reprint its prompt; left alone, a two-line PS1 fills the session with
     * empty prompts at twenty lines a minute and the terminal becomes useless.
     * Output is only ever dropped right after one of our own commands, and we
     * only inject when the session is quiet — so nothing of yours can land here.
     */
    private static final long PROMPT_ECHO_MS = 400;

    /**
     * And even then, only the prompt itself: a shell prints one prompt after a
     * command, two chunks at most for a two-line PS1. Anything beyond that is
     * yours and gets through, however soon it arrives.
     */
    private static final int PROMPT_ECHO_CHUNKS = 2;

    /** Extra room for the redraw that follows a resize held back by a capture. */
    private static final int REFLOW_CHUNKS = 6;

    /**
     * The console is never made narrower than this, however small the browser
     * window is. A terminal wraps every line to its width, and command output
     * folded at 40 characters is unreadable — 80 is the width every shell tool
     * has assumed for forty years. Wider than this and nothing is clamped: the
     * console matches the window exactly.
     */
    private static final int MIN_COLUMNS = 80;

    private final InputTracker input = new InputTracker();

    private PtyProcess process;
    private Writer toTerminal;
    private volatile Capture capture;
    private volatile long captureEndedAt;
    private volatile long lastOutputAt;
    private volatile int promptChunksLeft;
    private WinSize pendingSize;

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
            input.reset();
            lastOutputAt = 0;
            captureEndedAt = 0;

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

    /**
     * Whatever you type in the browser lands here, byte for byte, and is also
     * what tells the poller to keep its hands off the session.
     */
    public void type(String data) {
        input.record(data, System.currentTimeMillis());
        write(data);
    }

    /**
     * Why the poller must not write right now, or null when the session is free.
     * The message is shown as-is in the UI, so it says what you would do about
     * it rather than describing internal state.
     */
    public String busyReason(int quietSeconds) {
        if (input.hasPendingLine()) {
            return "Paused: you have a command half-typed. Press Enter or Ctrl+C and polling resumes.";
        }
        long quiet = Math.max(0, quietSeconds) * 1000L;
        if (quiet == 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - input.lastInputAt() < quiet) {
            return "Paused while you type — polling resumes when the terminal goes quiet.";
        }
        if (now - lastOutputAt < quiet) {
            return "Paused: something of yours is still running in the terminal.";
        }
        return null;
    }

    /** Writes to the PTY without touching the "you are busy" bookkeeping. */
    private void write(String data) {
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

    /**
     * Resizing reflows the console buffer. Doing that while a command's output
     * is being captured truncates it — which is how "Could not interpret
     * kubectl output" appears every time you drag the window edge. The new size
     * is held back and applied the moment the capture is done.
     */
    public void resize(int columns, int rows) {
        if (columns <= 0 || rows <= 0) {
            return;
        }
        WinSize size = new WinSize(Math.max(MIN_COLUMNS, columns), rows);
        synchronized (this) {
            if (capture != null) {
                pendingSize = size;
                return;
            }
            pendingSize = null;
        }
        applySize(size);
    }

    private void applySize(WinSize size) {
        PtyProcess current = process;
        if (current != null) {
            current.setWinSize(size);
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
            long since = System.currentTimeMillis() - captureEndedAt;
            if (since <= PROMPT_ECHO_MS && promptChunksLeft > 0) {
                promptChunksLeft--;
                return;
            }
            // Output that is not ours means a command of yours is still going;
            // the settle window skips the prompt our own capture just triggered.
            if (since > PROMPT_SETTLE_MS) {
                lastOutputAt = System.currentTimeMillis();
            }
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
        return runCaptured(command, props.monitor().timeoutSeconds());
    }

    public CompletableFuture<String> runCaptured(String command, int timeoutSeconds) {
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
                    Math.max(2, timeoutSeconds), TimeUnit.SECONDS);

            capture = new Capture(id, startMarker, endMarker, new StringBuilder(), future, timeout);

            // stderr is folded into stdout: when kubectl fails we want the reason
            write(echoStart + "; " + command + " 2>&1; " + echoEnd + "\n");
            return future;
        }
    }

    /**
     * Runs a command you asked for and waits for its output.
     *
     * Unlike the poller, this one does not give up when the session is busy: a
     * polling round lasts a fraction of a second, so it waits for its turn
     * rather than telling you to try again.
     */
    public String runOnDemand(String command, int timeoutSeconds, long slotWaitMillis) {
        long deadline = System.currentTimeMillis() + slotWaitMillis;
        while (captureInFlight() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the terminal");
            }
        }
        try {
            return runCaptured(command, timeoutSeconds)
                    .get(timeoutSeconds + 2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running the command");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException(
                    cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private synchronized boolean captureInFlight() {
        return capture != null;
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
        WinSize deferred = null;
        synchronized (this) {
            if (capture == current) {
                capture = null;
                captureEndedAt = System.currentTimeMillis();
                promptChunksLeft = PROMPT_ECHO_CHUNKS;
                deferred = pendingSize;
                pendingSize = null;
            }
        }
        if (deferred != null) {
            // Applying it reflows the console, and a console reflow reprints
            // what is in its screen buffer — which still holds the output we
            // just hid. Give the swallow window room to absorb that reprint.
            promptChunksLeft = PROMPT_ECHO_CHUNKS + REFLOW_CHUNKS;
            applySize(deferred);
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
