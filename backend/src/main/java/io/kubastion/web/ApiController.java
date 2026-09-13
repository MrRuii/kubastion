package io.kubastion.web;

import io.kubastion.config.KubastionProperties;
import io.kubastion.kubectl.KubectlCommands;
import io.kubastion.pods.PodWatchService;
import io.kubastion.pods.PodsSnapshot;
import io.kubastion.ssh.RemoteErrors;
import io.kubastion.ssh.SshRunner;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;

/**
 * API REST minima: lo snapshot dei pod (utile come fallback e per il debug) e
 * i log di un pod su richiesta.
 *
 * I log sono volutamente "a richiesta" e non in streaming: e' cio' che serve
 * premendo il pulsante, ed e' meno codice. Il follow, se servira', si aggiunge
 * dopo — vedi i non-goal del README.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final PodWatchService watch;
    private final SshRunner ssh;
    private final KubectlCommands kubectl;
    private final KubastionProperties props;

    public ApiController(PodWatchService watch, SshRunner ssh,
                         KubectlCommands kubectl, KubastionProperties props) {
        this.watch = watch;
        this.ssh = ssh;
        this.kubectl = kubectl;
        this.props = props;
    }

    @GetMapping("/pods")
    public PodsSnapshot pods() {
        return watch.snapshot();
    }

    /**
     * Il nome del pod arriva come query param e non nel path: i nomi Kubernetes
     * possono contenere punti, che nel path Spring tronca.
     */
    @GetMapping(value = "/logs", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> logs(@RequestParam String pod) throws IOException, InterruptedException {
        String command = kubectl.logs(pod, props.logs().tailLines());
        SshRunner.Result result = ssh.run(command, Duration.ofSeconds(30));

        if (!result.ok()) {
            return ResponseEntity.status(502).body(RemoteErrors.explain(result.stderr()));
        }
        String body = result.stdout().isBlank()
                ? "(nessuna riga di log nelle ultime " + props.logs().tailLines() + ")"
                : result.stdout();
        return ResponseEntity.ok(body);
    }
}
