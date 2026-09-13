package io.kubastion.pods;

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

    /** One healthy pod in the compact record format. */
    private static final String LIST =
            "api|demo|Running||node-01|2026-09-13T01:00:00Z|2026-09-13T01:00:00Z|true,0,,;@@";

    /** A real failure: an error where the records should be. */
    private static final String BROKEN = "error: unable to connect to the server";

    /** A monitor whose terminal returns the given payloads, one per poll. */
    private PodMonitorService monitor(String... payloads) {
        return monitorInNamespace("demo", payloads);
    }

    /** Same, with an explicit configured namespace ("" = derive it). */
    private PodMonitorService monitorInNamespace(String namespace, String... payloads) {
        TerminalService terminal = mock(TerminalService.class);
        when(terminal.isAlive()).thenReturn(true);
        when(terminal.busyReason(anyInt())).thenReturn(null);
        AtomicInteger i = new AtomicInteger(0);
        when(terminal.runCaptured(anyString())).thenAnswer(inv ->
                CompletableFuture.completedFuture(payloads[Math.min(i.getAndIncrement(), payloads.length - 1)]));

        KubastionProperties props = new KubastionProperties(
                new KubastionProperties.Terminal("", List.of()),
                new KubastionProperties.Kubectl("kubectl", namespace, ""),
                new KubastionProperties.Monitor(3, 15, 0));
        return new PodMonitorService(terminal, new KubectlCommands(props), props);
    }

    @Test
    void oneFailedPollAfterASuccessKeepsTheTable() {
        PodMonitorService service = monitor(LIST, BROKEN, LIST);

        service.tick();   // success
        service.tick();   // a single failed capture

        PodsSnapshot snap = service.snapshot();
        assertEquals(PodsSnapshot.State.MONITORING, snap.state(),
                () -> "one blip should not flip to ERROR: " + snap.message());
        assertEquals(1, snap.pods().size(), "the table must still hold its pods");
    }

    @Test
    void twoFailedPollsInARowDoEscalate() {
        PodMonitorService service = monitor(LIST, BROKEN, BROKEN);

        service.tick();   // success
        service.tick();   // failure #1 — absorbed
        service.tick();   // failure #2 — escalates

        assertEquals(PodsSnapshot.State.ERROR, service.snapshot().state());
    }

    @Test
    void aRecoveringPollClearsTheError() {
        PodMonitorService service = monitor(LIST, BROKEN, BROKEN, LIST);

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
        PodMonitorService service = monitor(BROKEN);

        service.tick();

        assertEquals(PodsSnapshot.State.ERROR, service.snapshot().state());
        assertTrue(service.snapshot().pods().isEmpty());
    }

    @Test
    void anErrorFromTheNamespaceProbeNeverBecomesTheNamespace() {
        // `kubectl config view --minify` fails with "current-context must exist
        // in order to minify" when there is no context. That is a message, not a
        // namespace, and it must not end up labelling the header.
        PodMonitorService service = monitorInNamespace("",             // derive it
                "",                                                    // no pods
                "error: current-context must exist in order to minify"); // probe reply

        service.tick();

        assertEquals("", service.snapshot().namespace());
    }

    @Test
    void aDerivedNamespaceIsForgottenWhenMonitoringStops() {
        // It was true of the session you were in. Disconnect, and it would still
        // be naming the dev cluster you left — a header quietly lying about what
        // you are looking at.
        PodMonitorService service = monitorInNamespace("", "", "team-billing");

        service.tick();
        assertEquals("team-billing", service.snapshot().namespace());

        service.stop();

        assertEquals("", service.snapshot().namespace());
    }
}
