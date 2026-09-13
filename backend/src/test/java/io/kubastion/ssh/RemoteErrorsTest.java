package io.kubastion.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value of this class is not technical: it is that the UI shows a sentence
 * telling you what to do, instead of "Permission denied (publickey)".
 */
class RemoteErrorsTest {

    @Test
    void expiredCredentialTellsYouToReloadTheKey() {
        String message = RemoteErrors.explain("mike@jump: Permission denied (publickey).");

        assertTrue(message.toLowerCase().contains("credential"), message);
        assertTrue(message.contains("ssh-add"), message);
    }

    @Test
    void unresolvableHostPointsAtTheVpn() {
        String message = RemoteErrors.explain("ssh: Could not resolve hostname jump: No such host");

        assertTrue(message.toUpperCase().contains("VPN"), message);
    }

    @Test
    void timeoutPointsAtTheVpn() {
        String message = RemoteErrors.explain("ssh: connect to host jump port 22: Connection timed out");

        assertTrue(message.toUpperCase().contains("VPN"), message);
    }

    @Test
    void missingKubectlPointsAtTheConfiguration() {
        String message = RemoteErrors.explain("bash: kubectl: command not found");

        assertTrue(message.contains("kubastion.kubectl.binary"), message);
    }

    @Test
    void unreachableClusterIsNotAnSshProblem() {
        // ssh worked here: it is kubectl that cannot talk to the cluster.
        String message = RemoteErrors.explain("Unable to connect to the server: dial tcp: i/o timeout");

        assertTrue(message.contains("cluster"), message);
    }

    @Test
    void insufficientPermissionsIsItsOwnCase() {
        String message = RemoteErrors.explain(
                "Error from server (Forbidden): pods is forbidden: cannot list resource \"pods\"");

        assertTrue(message.toLowerCase().contains("permission"), message);
    }

    @Test
    void emptyStderrStillProducesASensibleSentence() {
        String message = RemoteErrors.explain("");

        assertTrue(message.toLowerCase().contains("retrying"), message);
        assertEquals(message, RemoteErrors.explain(null));
    }

    @Test
    void unknownErrorReportsOnlyTheFirstUsefulLine() {
        String message = RemoteErrors.explain("""

                something unexpected happened
                a detail line nobody needs
                """);

        assertEquals("something unexpected happened", message);
    }
}
