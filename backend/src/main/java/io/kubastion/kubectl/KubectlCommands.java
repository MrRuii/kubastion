package io.kubastion.kubectl;

import io.kubastion.config.KubastionProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Builds the kubectl command strings executed on the remote machine.
 *
 * Everything that ends up on the command line is validated: the remote shell
 * runs whatever we hand it, so no value — not even one that comes from the
 * config file — gets through unchecked.
 */
@Component
public class KubectlCommands {

    /** Kubernetes resource names (RFC 1123): lowercase, digits, dashes, dots. */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$");

    /** The binary may be a path, but never contains shell metacharacters. */
    private static final Pattern SAFE_BINARY = Pattern.compile("^[A-Za-z0-9._/\\\\:-]{1,200}$");

    private final String binary;
    private final String namespace;
    private final String context;

    public KubectlCommands(KubastionProperties props) {
        this.binary = requireBinary(props.kubectl().binary());
        // The namespace is optional. Left blank, kubastion passes no -n and runs
        // `kubectl get pods` exactly as you would by hand — against the default
        // namespace of the session you logged into. You configure it only when
        // you want a namespace *other* than that default.
        String ns = props.kubectl().namespace();
        this.namespace = (ns == null || ns.isBlank()) ? "" : requireName(ns, "kubectl.namespace");
        String ctx = props.kubectl().context();
        this.context = (ctx == null || ctx.isBlank()) ? "" : requireName(ctx, "kubectl.context");
    }

    /**
     * Lists pods as JSON.
     *
     * Polling is the right call here, not a fallback: the terminal session is
     * shared with the user, and a `--watch` would hold it open forever and stop
     * them from working.
     */
    public String getPods() {
        return base() + " get pods -o json";
    }

    /**
     * Builds one of the catalogue commands.
     *
     * The pod name comes from the browser, so it is validated here rather than
     * trusted: the UI only ever sends back a name it was given, but the shell
     * on the other end does not know that.
     */
    public String build(ClusterCommand command, String pod, Integer tailLines) {
        String target = "";
        if (command.needsPod()) {
            target = requireName(pod, "pod");
        }
        int tail = command.tailable() ? clampTail(tailLines) : 0;
        String arguments = command.arguments(target, tail);

        return command.scope() == ClusterCommand.Scope.CLUSTER
                ? clusterBase() + " " + arguments
                : base() + " " + arguments;
    }

    public String namespace() {
        return namespace;
    }

    /**
     * Asks the remote kubeconfig which namespace the current context defaults to
     * — the one `kubectl get pods` uses when you type it by hand. Read-only, and
     * only used to label the UI when no namespace was configured.
     */
    public String currentNamespace() {
        return clusterBase() + " config view --minify --output jsonpath={..namespace}";
    }

    private String base() {
        // No namespace configured → no -n, so the command uses the default
        // namespace of the session you are in, just like typing it yourself.
        return namespace.isEmpty() ? clusterBase() : clusterBase() + " -n " + namespace;
    }

    private String clusterBase() {
        StringBuilder sb = new StringBuilder(binary);
        if (!context.isEmpty()) {
            sb.append(" --context=").append(context);
        }
        return sb.toString();
    }

    /**
     * A log tail has to be bounded. Unbounded output would have to travel
     * through the terminal a line at a time and would take longer than anyone
     * is willing to wait, so the UI picks from a range and anything else is
     * pulled back into it.
     */
    private static int clampTail(Integer requested) {
        int value = requested == null ? 200 : requested;
        return Math.min(5000, Math.max(10, value));
    }

    /** Validates a resource, namespace or context name. */
    public static String requireName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing value for " + what);
        }
        String trimmed = value.trim();
        if (!SAFE_NAME.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid value for " + what + ": " + value);
        }
        return trimmed;
    }

    private static String requireBinary(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing value for kubectl.binary");
        }
        String trimmed = value.trim();
        if (!SAFE_BINARY.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid kubectl path: " + value);
        }
        return trimmed;
    }
}
