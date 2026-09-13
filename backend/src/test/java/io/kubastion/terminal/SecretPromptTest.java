package io.kubastion.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cost of the two mistakes is not symmetric. A false positive pauses one
 * poll for three seconds. A false negative types a kubectl command into an ssh
 * password prompt — a failed authentication attempt, three of which lock you out
 * of the machine you were trying to reach, with your command left in a log as if
 * it were a password. So these tests lean hard on the cautious side.
 */
class SecretPromptTest {

    @Test
    void anSshPasswordPromptIsWaiting() {
        assertTrue(SecretPrompt.isWaiting("you@jump-host's password: "));
        assertTrue(SecretPrompt.isWaiting("Password:"));
        assertTrue(SecretPrompt.isWaiting("mike@10.0.0.7's password:"));
    }

    @Test
    void aKeyPassphrasePromptIsWaiting() {
        assertTrue(SecretPrompt.isWaiting("Enter passphrase for key '/c/Users/mike/.ssh/id_ed25519':"));
    }

    @Test
    void aSecondFactorPromptIsWaiting() {
        assertTrue(SecretPrompt.isWaiting("Verification code:"));
        assertTrue(SecretPrompt.isWaiting("One-time password:"));
        assertTrue(SecretPrompt.isWaiting("Enter PIN:"));
    }

    @Test
    void theHostKeyQuestionIsWaitingToo() {
        // Injecting here would answer the fingerprint question with a kubectl
        // command, which is neither "yes" nor "no" and leaves ssh stuck.
        assertTrue(SecretPrompt.isWaiting(
                "Are you sure you want to continue connecting (yes/no/[fingerprint])? "));
        assertTrue(SecretPrompt.isWaiting("Are you sure you want to continue connecting (yes/no)?"));
    }

    @Test
    void anAnsweredPromptIsNoLongerWaiting() {
        // The newline means the prompt was submitted and the line closed.
        assertFalse(SecretPrompt.isWaiting("you@jump-host's password: \r\nLast login: Tue"));
    }

    @Test
    void anOrdinaryShellPromptIsNotWaiting() {
        assertFalse(SecretPrompt.isWaiting("you@jump-host:~$ "));
        assertFalse(SecretPrompt.isWaiting("mike2@MikePC MINGW64 /c/Coding Project (main)\n$ "));
    }

    @Test
    void theWordInPassingIsNotAPrompt() {
        // Only a prompt sitting at the very end counts; the same words inside a
        // log line are just text the command printed.
        assertFalse(SecretPrompt.isWaiting("INFO rotating password: done\nnext line here"));
        assertFalse(SecretPrompt.isWaiting("kubectl get secrets\nNAME TYPE DATA AGE"));
    }

    @Test
    void nothingAtAllIsNotWaiting() {
        assertFalse(SecretPrompt.isWaiting(null));
        assertFalse(SecretPrompt.isWaiting(""));
        assertFalse(SecretPrompt.isWaiting("   \r\n "));
    }
}
