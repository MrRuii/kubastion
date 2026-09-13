package io.kubastion.kubectl;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The commands kubastion is willing to run for you.
 *
 * A fixed list, not free text. Whatever is typed in the browser reaches a real
 * shell on a machine inside the network, so the UI never gets to compose a
 * command — it picks one of these by id and, at most, names a pod.
 *
 * Everything here is read-only. Nothing applies, deletes, scales or edits: if
 * you want to change the cluster, you have a terminal right there and your own
 * credentials, and kubastion should not be the thing that made it easy.
 */
public enum ClusterCommand {

    // ----------------------------------------------------------------- pods

    POD_LOGS("Logs", Scope.POD, true,
            "Recent output from every container in the pod."),
    POD_LOGS_PREVIOUS("Logs (previous)", Scope.POD, true,
            "Output from the run before the last restart — where a crash loop explains itself."),
    POD_DESCRIBE("Describe", Scope.POD, false,
            "Conditions, image, probes and the recent events for this pod."),
    POD_EVENTS("Events", Scope.POD, false,
            "What Kubernetes has done to this pod lately, newest last."),
    POD_YAML("YAML", Scope.POD, false,
            "The full manifest as the cluster sees it."),

    // ------------------------------------------------------------ namespace

    NS_EVENTS("Events", Scope.NAMESPACE, false,
            "Everything that happened in the namespace, newest last."),
    NS_DEPLOYMENTS("Deployments", Scope.NAMESPACE, false,
            "Desired and available replicas per deployment."),
    NS_SERVICES("Services", Scope.NAMESPACE, false,
            "Services, their type, cluster IP and ports."),
    NS_INGRESSES("Ingresses", Scope.NAMESPACE, false,
            "Hosts and paths routed into the namespace."),
    NS_CONFIGMAPS("ConfigMaps", Scope.NAMESPACE, false,
            "ConfigMaps and how many keys each one holds."),
    NS_SECRETS("Secrets", Scope.NAMESPACE, false,
            "Secret names, types and key counts. Values are never read."),
    NS_NODES("Nodes", Scope.CLUSTER, false,
            "Nodes, their roles, version and readiness."),
    NS_TOP_PODS("Top pods", Scope.NAMESPACE, false,
            "CPU and memory per pod. Needs metrics-server on the cluster.");

    public enum Scope {
        /** Runs against one pod, whose name you pick from the table. */
        POD,
        /** Runs against the configured namespace. */
        NAMESPACE,
        /** Not namespaced at all. */
        CLUSTER
    }

    private final String label;
    private final Scope scope;
    private final boolean tailable;
    private final String description;

    ClusterCommand(String label, Scope scope, boolean tailable, String description) {
        this.label = label;
        this.scope = scope;
        this.tailable = tailable;
        this.description = description;
    }

    public String id() {
        return name().toLowerCase().replace('_', '-');
    }

    public String label() {
        return label;
    }

    public Scope scope() {
        return scope;
    }

    /** Whether a line limit applies — logs are the only unbounded output here. */
    public boolean tailable() {
        return tailable;
    }

    public String description() {
        return description;
    }

    public boolean needsPod() {
        return scope == Scope.POD;
    }

    public static Optional<ClusterCommand> byId(String id) {
        return Arrays.stream(values()).filter(command -> command.id().equals(id)).findFirst();
    }

    public static List<ClusterCommand> all() {
        return List.of(values());
    }

    /**
     * The kubectl arguments for this command, after the binary, the context and
     * the namespace. The pod name is already validated by the caller.
     */
    String arguments(String pod, int tailLines) {
        return switch (this) {
            case POD_LOGS -> "logs " + pod + " --all-containers=true --timestamps --tail=" + tailLines;
            // --previous is the only way to read a container that already died,
            // which is exactly the case you are looking at when it crash-loops.
            case POD_LOGS_PREVIOUS -> "logs " + pod + " --all-containers=true --previous --tail=" + tailLines;
            case POD_DESCRIBE -> "describe pod " + pod;
            case POD_EVENTS -> "get events --field-selector involvedObject.name=" + pod
                    + " --sort-by=.lastTimestamp";
            case POD_YAML -> "get pod " + pod + " -o yaml";

            case NS_EVENTS -> "get events --sort-by=.lastTimestamp";
            case NS_DEPLOYMENTS -> "get deployments -o wide";
            case NS_SERVICES -> "get services -o wide";
            case NS_INGRESSES -> "get ingress";
            case NS_CONFIGMAPS -> "get configmaps";
            // `get secrets` lists names, types and key counts. Reading a value
            // would mean printing a live credential into a browser tab, so the
            // catalogue simply has no command that can do it.
            case NS_SECRETS -> "get secrets";
            case NS_NODES -> "get nodes -o wide";
            case NS_TOP_PODS -> "top pods";
        };
    }
}
