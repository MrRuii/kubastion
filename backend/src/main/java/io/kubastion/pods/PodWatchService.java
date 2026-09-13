package io.kubastion.pods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.config.KubastionProperties;
import io.kubastion.kubectl.KubectlCommands;
import io.kubastion.ssh.RemoteErrors;
import io.kubastion.ssh.SshRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Tiene aperto un unico processo ssh che esegue `kubectl get pods --watch` sul
 * jump host, ne consuma lo stream di eventi JSON e mantiene lo stato dei pod.
 *
 * Il punto centrale: quando la credenziale a vita breve scade, ssh muore e lo
 * stream finisce. Qui non e' un errore fatale — si segnala alla UI, si aspetta,
 * e si riprova. Quando ricarichi la chiave, torna su da solo senza che tu
 * debba fare nulla.
 */
@Service
public class PodWatchService {

    private static final Logger log = LoggerFactory.getLogger(PodWatchService.class);

    private final SshRunner ssh;
    private final KubectlCommands kubectl;
    private final KubastionProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, PodView> pods = new ConcurrentHashMap<>();
    private final List<Consumer<PodsSnapshot>> listeners = new CopyOnWriteArrayList<>();

    private volatile PodsSnapshot.Connection connection = PodsSnapshot.Connection.STARTING;
    private volatile String message = "Avvio…";
    private volatile boolean running;
    private volatile Process process;
    private Thread worker;

    public PodWatchService(SshRunner ssh, KubectlCommands kubectl, KubastionProperties props) {
        this.ssh = ssh;
        this.kubectl = kubectl;
        this.props = props;
    }

    @PostConstruct
    void start() {
        running = true;
        worker = Thread.ofVirtual().name("pod-watch").start(this::loop);
    }

    @PreDestroy
    void stop() {
        running = false;
        Process current = process;
        if (current != null) {
            current.destroy();
        }
        if (worker != null) {
            worker.interrupt();
        }
    }

    public void addListener(Consumer<PodsSnapshot> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<PodsSnapshot> listener) {
        listeners.remove(listener);
    }

    public PodsSnapshot snapshot() {
        List<PodView> ordered = pods.values().stream()
                .sorted(Comparator.comparing(PodView::name))
                .toList();
        return new PodsSnapshot(connection, message, kubectl.namespace(), ordered, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------- interno

    private void loop() {
        while (running) {
            String failure = watchOnce();
            if (!running) {
                return;
            }
            pods.clear();
            publish(PodsSnapshot.Connection.RECONNECTING, failure);
            sleep(Duration.ofSeconds(Math.max(1, props.watch().retryDelaySeconds())));
        }
    }

    /** Apre lo stream e lo consuma finche' regge. Ritorna il motivo della caduta. */
    private String watchOnce() {
        StringBuilder stderr = new StringBuilder();
        Process current = null;
        try {
            current = ssh.start(kubectl.watchPods());
            process = current;
            Process forReader = current;
            Thread errorReader = Thread.ofVirtual()
                    .start(() -> SshRunner.readInto(forReader.getErrorStream(), stderr));

            try (InputStream out = current.getInputStream()) {
                MappingIterator<JsonNode> events = mapper.readerFor(JsonNode.class).readValues(out);
                pods.clear();
                publish(PodsSnapshot.Connection.CONNECTED, "");
                while (running && events.hasNextValue()) {
                    apply(events.nextValue());
                    publish(PodsSnapshot.Connection.CONNECTED, "");
                }
            } finally {
                errorReader.join(Duration.ofSeconds(1));
            }
        } catch (IOException e) {
            stderr.append(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (current != null) {
                current.destroy();
            }
            process = null;
        }
        String reason = RemoteErrors.explain(stderr.toString());
        log.debug("watch terminato: {}", reason);
        return reason;
    }

    /** Applica un evento ADDED / MODIFIED / DELETED allo stato in memoria. */
    private void apply(JsonNode event) {
        JsonNode pod = event.path("object");
        String name = pod.path("metadata").path("name").asText("");
        if (name.isEmpty()) {
            return;
        }
        if ("DELETED".equals(event.path("type").asText())) {
            pods.remove(name);
        } else {
            pods.put(name, PodView.from(pod));
        }
    }

    private void publish(PodsSnapshot.Connection state, String text) {
        this.connection = state;
        this.message = text;
        PodsSnapshot snapshot = snapshot();
        for (Consumer<PodsSnapshot> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (Exception e) {
                log.debug("listener in errore, ignorato: {}", e.toString());
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
