package io.kubastion.pods;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.config.KubastionProperties;
import io.kubastion.kubectl.KubectlCommands;
import io.kubastion.terminal.TerminalService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * When you press "start monitoring", this service injects `kubectl get pods`
 * into the terminal session every few seconds and parses the output.
 *
 * The assumption is explicit: getting to the right machine is your job, done by
 * hand in the terminal. kubastion does not guess where you are — it runs the
 * command wherever you happen to be.
 */
@Service
public class PodMonitorService {

    private static final Logger log = LoggerFactory.getLogger(PodMonitorService.class);

    private final TerminalService terminal;
    private final KubectlCommands kubectl;
    private final KubastionProperties props;
    private final ObjectMapper mapper;
    private final List<Consumer<PodsSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pod-monitor");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> task;
    private volatile List<PodView> pods = List.of();
    private volatile PodsSnapshot.State state = PodsSnapshot.State.IDLE;
    private volatile String message = "";
    private volatile long updatedAt;

    public PodMonitorService(TerminalService terminal, KubectlCommands kubectl,
                             KubastionProperties props, ObjectMapper mapper) {
        this.terminal = terminal;
        this.kubectl = kubectl;
        this.props = props;
        this.mapper = mapper;
    }

    public synchronized void start() {
        if (task != null && !task.isCancelled()) {
            return;
        }
        int interval = Math.max(1, props.monitor().intervalSeconds());
        state = PodsSnapshot.State.MONITORING;
        message = "";
        task = scheduler.scheduleWithFixedDelay(this::tick, 0, interval, TimeUnit.SECONDS);
        log.info("Pod monitoring started, every {}s", interval);
        publish();
    }

    public synchronized void stop() {
        ScheduledFuture<?> current = task;
        if (current != null) {
            current.cancel(false);
            task = null;
        }
        state = PodsSnapshot.State.IDLE;
        message = "";
        pods = List.of();
        updatedAt = 0;
        log.info("Pod monitoring stopped");
        publish();
    }

    public boolean isRunning() {
        ScheduledFuture<?> current = task;
        return current != null && !current.isCancelled();
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public void addListener(Consumer<PodsSnapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<PodsSnapshot> listener) {
        listeners.remove(listener);
    }

    public PodsSnapshot snapshot() {
        return new PodsSnapshot(state, message, kubectl.namespace(), pods, updatedAt);
    }

    // ------------------------------------------------------------- one round

    private void tick() {
        if (!terminal.isAlive()) {
            fail("Terminal is not running. Open the page and log in.");
            return;
        }
        // The session is yours first. Injecting while you are mid-command would
        // corrupt the line you are writing, so the table waits instead.
        String busy = terminal.busyReason(props.monitor().quietSeconds());
        if (busy != null) {
            pause(busy);
            return;
        }
        try {
            // scheduleWithFixedDelay: the next round only starts once this one
            // is done, so two commands can never overlap in the session.
            String payload = terminal.runCaptured(kubectl.getPods())
                    .get(Math.max(2, props.monitor().timeoutSeconds()) + 2L, TimeUnit.SECONDS);
            log.debug("captured {} chars: [{}]", payload.length(), excerpt(payload));
            parse(payload);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            fail(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }

    private void parse(String payload) {
        switch (PodListParser.parse(payload, mapper)) {
            case PodListParser.Result.Pods found -> {
                pods = found.pods();
                state = PodsSnapshot.State.MONITORING;
                message = "";
                updatedAt = System.currentTimeMillis();
                publish();
            }
            case PodListParser.Result.Failure failure -> fail(failure.message());
        }
    }

    private void fail(String reason) {
        state = PodsSnapshot.State.ERROR;
        message = reason == null ? "Unknown error" : reason;
        publish();
    }

    /** Holds the last known pods on screen, and says why they are not moving. */
    private void pause(String reason) {
        boolean changed = state != PodsSnapshot.State.PAUSED || !reason.equals(message);
        state = PodsSnapshot.State.PAUSED;
        message = reason;
        if (changed) {
            publish();
        }
    }

    /** Enough of the payload to tell truncation from corruption in a log line. */
    private static String excerpt(String payload) {
        String flat = payload.replace("\r", "\\r").replace("\n", "\\n");
        return flat.length() <= 160 ? flat
                : flat.substring(0, 80) + " … " + flat.substring(flat.length() - 80);
    }

    private void publish() {
        PodsSnapshot snapshot = snapshot();
        for (Consumer<PodsSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                log.debug("listener failed, ignored: {}", e.toString());
            }
        }
    }
}
