package io.kubastion.pods;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.config.KubastionProperties;
import io.kubastion.kubectl.KubectlCommands;
import io.kubastion.terminal.TerminalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resize repaint, or one slow round, can make a single poll come back empty.
 * Once a table is on screen, that blip must not repaint the whole UI red — the
 * table holds, and only a run of failures escalates to an error.
 */
class PodMonitorFailureToleranceTest {

    private static final String LIST = """
            {"items":[{"metadata":{"name":"api"},"status":{"phase":"Running",
              "containerStatuses":[{"ready":true,"restartCount":0,"state":{"running":{}}}]}}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    /** A monitor whose terminal returns the given payloads, one per poll. */
    private PodMonitorService monitor(String... payloads) {
        TerminalService terminal = mock(TerminalService.class);
        when(terminal.isAlive()).thenReturn(true);
        when(terminal.busyReason(anyInt())).thenReturn(null);
        AtomicInteger i = new AtomicInteger(0);
        when(terminal.runCaptured(anyString())).thenAnswer(inv ->
                CompletableFuture.completedFuture(payloads[Math.min(i.getAndIncrement(), payloads.length - 1)]));

        KubastionProperties props = new KubastionProperties(
                new KubastionProperties.Terminal("", List.of()),
                new KubastionProperties.Kubectl("kubectl", "demo", ""),
                new KubastionProperties.Monitor(3, 15, 0));
        return new PodMonitorService(terminal, new KubectlCommands(props), props, mapper);
    }

    @Test
    void oneEmptyPollAfterASuccessKeepsTheTable() {
        PodMonitorService service = monitor(LIST, "", LIST);

        service.tick();   // success
        service.tick();   // a single empty capture

        PodsSnapshot snap = service.snapshot();
        assertEquals(PodsSnapshot.State.MONITORING, snap.state(),
                () -> "one blip should not flip to ERROR: " + snap.message());
        assertEquals(1, snap.pods().size(), "the table must still hold its pods");
    }

    @Test
    void twoEmptyPollsInARowDoEscalate() {
        PodMonitorService service = monitor(LIST, "", "");

        service.tick();   // success
        service.tick();   // empty #1 — absorbed
        service.tick();   // empty #2 — escalates

        assertEquals(PodsSnapshot.State.ERROR, service.snapshot().state());
    }

    @Test
    void aRecoveringPollClearsTheError() {
        PodMonitorService service = monitor(LIST, "", "", LIST);

        service.tick();
        service.tick();
        service.tick();   // now ERROR
        assertEquals(PodsSnapshot.State.ERROR, service.snapshot().state());

        service.tick();   // recovers
        assertEquals(PodsSnapshot.State.MONITORING, service.snapshot().state());
    }

    @Test
    void theVeryFirstPollFailingSurfacesImmediately() {
        // Nothing on screen yet, so there is nothing to protect — say so at once.
        PodMonitorService service = monitor("");

        service.tick();

        assertEquals(PodsSnapshot.State.ERROR, service.snapshot().state());
        assertTrue(service.snapshot().pods().isEmpty());
    }
}
