package io.kubastion.web;

import io.kubastion.pods.PodMonitorService;
import io.kubastion.pods.PodsSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Turns pod polling on and off. Live state arrives over the WebSocket. */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final PodMonitorService monitor;

    public MonitorController(PodMonitorService monitor) {
        this.monitor = monitor;
    }

    @PostMapping("/start")
    public PodsSnapshot start() {
        monitor.start();
        return monitor.snapshot();
    }

    @PostMapping("/stop")
    public PodsSnapshot stop() {
        monitor.stop();
        return monitor.snapshot();
    }

    @GetMapping
    public PodsSnapshot current() {
        return monitor.snapshot();
    }
}
