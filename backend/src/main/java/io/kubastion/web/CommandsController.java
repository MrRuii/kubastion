package io.kubastion.web;

import io.kubastion.config.KubastionProperties;
import io.kubastion.kubectl.ClusterCommand;
import io.kubastion.kubectl.KubectlCommands;
import io.kubastion.ssh.RemoteErrors;
import io.kubastion.terminal.TerminalService;
import io.kubastion.terminal.TerminalText;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Runs one of the catalogue commands in the session you opened, and hands the
 * output back for the UI to show in its own window.
 *
 * The browser never sends a command line, only an id from {@link ClusterCommand}
 * and at most a pod name — so nothing typed in a web page can become something
 * executed on a machine inside the network.
 */
@RestController
@RequestMapping("/api/commands")
public class CommandsController {

    /** Long enough for a thousand log lines to make it through the terminal. */
    private static final int MIN_TIMEOUT_SECONDS = 25;

    /** A polling round is short; waiting for it beats refusing the request. */
    private static final long SLOT_WAIT_MILLIS = 8_000;

    private final TerminalService terminal;
    private final KubectlCommands kubectl;
    private final KubastionProperties props;

    public CommandsController(TerminalService terminal, KubectlCommands kubectl,
                              KubastionProperties props) {
        this.terminal = terminal;
        this.kubectl = kubectl;
        this.props = props;
    }

    @GetMapping
    public List<CommandInfo> catalogue() {
        return ClusterCommand.all().stream().map(CommandInfo::of).toList();
    }

    @PostMapping("/run")
    public RunResult run(@RequestBody RunRequest request) {
        ClusterCommand command = ClusterCommand.byId(request.id())
                .orElseThrow(() -> new IllegalArgumentException("Unknown command: " + request.id()));

        // The session is yours: a half-typed line means this command would land
        // in the middle of it, so it is refused with the reason rather than run.
        String busy = terminal.busyReason(0);
        if (busy != null) {
            throw new IllegalStateException(busy);
        }

        String line = kubectl.build(command, request.pod(), request.tailLines());
        int timeout = Math.max(MIN_TIMEOUT_SECONDS, props.monitor().timeoutSeconds());

        String output;
        try {
            output = TerminalText.clean(terminal.runOnDemand(line, timeout, SLOT_WAIT_MILLIS)).strip();
        } catch (IllegalStateException e) {
            throw new IllegalStateException(RemoteErrors.explain(e.getMessage()));
        }

        return new RunResult(
                command.id(),
                title(command, request.pod()),
                line,
                output,
                System.currentTimeMillis());
    }

    private static String title(ClusterCommand command, String pod) {
        return command.needsPod() ? command.label() + " · " + pod : command.label();
    }

    public record RunRequest(String id, String pod, Integer tailLines) {
    }

    public record RunResult(String id, String title, String command, String output, long ranAt) {
    }

    public record CommandInfo(String id, String label, String scope, String description,
                              boolean needsPod, boolean tailable) {

        static CommandInfo of(ClusterCommand command) {
            return new CommandInfo(command.id(), command.label(), command.scope().name(),
                    command.description(), command.needsPod(), command.tailable());
        }
    }
}
