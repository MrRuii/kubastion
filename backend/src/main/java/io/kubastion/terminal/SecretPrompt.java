package io.kubastion.terminal;

import java.util.regex.Pattern;

/**
 * Recognises a session sitting on a prompt that is waiting for a secret, or for
 * a yes/no answer, so that nothing is ever injected into it.
 *
 * This is the hard edge of the project's one promise: kubastion never asks for,
 * stores or transmits your key, password or OTP. Typing a `kubectl` line into an
 * ssh password prompt would break that promise in the worst possible way — the
 * command becomes a failed authentication attempt, three of them lock you out,
 * and the text you typed is now in a log somewhere as a password.
 *
 * Detection is deliberately generous. A false positive costs one paused poll;
 * a false negative costs an account lockout.
 */
final class SecretPrompt {

    /**
     * A prompt is only a prompt at the very end of what has been printed — the
     * cursor is sitting after it, waiting. The same words in the middle of a log
     * line are just text.
     */
    private static final Pattern WAITING = Pattern.compile(
            "(?i)("
                    + "password[^\\r\\n]{0,40}:"
                    + "|passphrase[^\\r\\n]{0,60}:"
                    + "|verification code[^\\r\\n]{0,20}:"
                    + "|one[- ]time[^\\r\\n]{0,30}:"
                    + "|\\botp\\b[^\\r\\n]{0,20}:"
                    + "|enter pin[^\\r\\n]{0,20}:"
                    + "|\\(yes/no(?:/\\[fingerprint\\])?\\)\\?"
                    + ")[ \\t]*$");

    private SecretPrompt() {
    }

    /**
     * @param recentOutput the tail of what the terminal has shown, ANSI already
     *                     stripped
     */
    static boolean isWaiting(String recentOutput) {
        if (recentOutput == null || recentOutput.isEmpty()) {
            return false;
        }
        // Trailing newlines mean the prompt was answered and the line closed.
        String tail = recentOutput.stripTrailing();
        return WAITING.matcher(tail).find();
    }
}
