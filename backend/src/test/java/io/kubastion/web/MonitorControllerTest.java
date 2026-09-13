package io.kubastion.web;

import io.kubastion.pods.PodMonitorService;
import io.kubastion.terminal.TerminalService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Stopping means "I am done with this machine", so it has to hang up as well as
 * stop polling — otherwise you are left logged into a cluster the UI has stopped
 * telling you anything about.
 */
class MonitorControllerTest {

    private final PodMonitorService monitor = mock(PodMonitorService.class);
    private final TerminalService terminal = mock(TerminalService.class);
    private final MonitorController controller = new MonitorController(monitor, terminal);

    @Test
    void stoppingAlsoEndsTheSession() {
        controller.stop();

        verify(monitor).stop();
        verify(terminal).restart();
    }

    @Test
    void startingNeverTouchesTheSession() {
        // Starting must leave the login you just made completely alone.
        controller.start();

        verify(monitor).start();
        verify(terminal, never()).restart();
    }
}
