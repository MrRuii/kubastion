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
 * Quando premi "start monitoring", questo servizio inietta `kubectl get pods`
 * nella sessione del terminale ogni N secondi e ne parsa l'output.
 *
 * Il presupposto e' esplicito: tocca a te esserti gia' collegato alla macchina
 * giusta dentro il terminale. kubastion non indovina dove sei — esegue il
 * comando dove sei tu.
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
        log.info("Monitoraggio pod avviato, intervallo {}s", interval);
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
        log.info("Monitoraggio pod fermato");
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
        return new PodsSnapshot(state, message, kubectl.namespace(), pods, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- il giro

    private void tick() {
        if (!terminal.isAlive()) {
            fail("Terminale non attivo: apri la pagina e collegati.");
            return;
        }
        try {
            // scheduleWithFixedDelay: il giro successivo parte solo a questo
            // concluso, quindi due comandi non si sovrappongono mai.
            String payload = terminal.runCaptured(kubectl.getPods())
                    .get(Math.max(2, props.monitor().timeoutSeconds()) + 2L, TimeUnit.SECONDS);
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
                publish();
            }
            case PodListParser.Result.Failure failure -> fail(failure.message());
        }
    }

    private void fail(String reason) {
        state = PodsSnapshot.State.ERROR;
        message = reason == null ? "Errore sconosciuto" : reason;
        publish();
    }

    private void publish() {
        PodsSnapshot snapshot = snapshot();
        for (Consumer<PodsSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                log.debug("listener in errore, ignorato: {}", e.toString());
            }
        }
    }
}
