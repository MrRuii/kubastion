package io.kubastion.terminal;

/**
 * Keeps track of whether you have a half-typed line sitting at the prompt.
 *
 * This is what stops the poller from writing into the session while you are in
 * the middle of a command. A quiet timer alone is not enough: you can type
 * `kubectl get pods -n ` and stop to think for ten seconds, and an injection at
 * that moment would corrupt the line you were writing.
 *
 * The rule is deliberately conservative — while anything is pending, nothing is
 * injected — so the worst case is a paused table, never a mangled command.
 */
final class InputTracker {

    private static final char ENTER = '\r';
    private static final char NEWLINE = '\n';
    private static final char BACKSPACE = '\b';
    private static final char DELETE = 0x7f;
    private static final char CTRL_C = 0x03;
    private static final char CTRL_D = 0x04;
    private static final char CTRL_U = 0x15;
    private static final char ESCAPE = 0x1b;

    private int pending;
    private long lastInputAt;

    /** Feeds in exactly what the browser typed, byte for byte. */
    synchronized void record(String data, long now) {
        if (data == null || data.isEmpty()) {
            return;
        }
        lastInputAt = now;

        boolean inEscape = false;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);

            // Arrow keys, function keys and mouse events arrive as escape
            // sequences. They are not characters on the line, so they must not
            // count as one — but they end on a letter, which would.
            if (inEscape) {
                if (Character.isLetter(c) || c == '~') {
                    inEscape = false;
                }
                continue;
            }

            switch (c) {
                case ESCAPE -> inEscape = true;
                case ENTER, NEWLINE, CTRL_C, CTRL_D, CTRL_U -> pending = 0;
                case BACKSPACE, DELETE -> pending = Math.max(0, pending - 1);
                default -> {
                    if (c >= ' ') {
                        pending++;
                    }
                }
            }
        }
    }

    /** True while there is something typed at the prompt and not yet submitted. */
    synchronized boolean hasPendingLine() {
        return pending > 0;
    }

    synchronized long lastInputAt() {
        return lastInputAt;
    }

    /** A new shell starts at an empty prompt; whatever we believed is stale. */
    synchronized void reset() {
        pending = 0;
        lastInputAt = 0;
    }
}
