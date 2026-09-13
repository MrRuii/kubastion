package io.kubastion.web;

import io.kubastion.pods.PodMonitorService;
import io.kubastion.pods.PodsSnapshot;
import io.kubastion.terminal.TerminalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Turns pod polling on and off. Live state arrives over the WebSocket. */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final PodMonitorService monitor;
    private final TerminalService terminal;

    public MonitorController(PodMonitorService monitor, TerminalService terminal) {
        this.monitor = monitor;
        this.terminal = terminal;
    }

    @PostMapping("/start")
    public PodsSnapshot start() {
        monitor.start();
        return monitor.snapshot();
    }

    /**
     * Stops polling and hangs up: the session ends and a fresh local shell takes
     * its place, leaving you exactly where you were before you connected.
     *
     * Stopping means "I am done with this machine", not "pause for a moment" —
     * pausing is already automatic while you are using the terminal.
     */
    @PostMapping("/stop")
    public PodsSnapshot stop() {
        monitor.stop();
        terminal.restart();
        return monitor.snapshot();
    }

    @GetMapping
    public PodsSnapshot current() {
        return monitor.snapshot();
    }
}
