package io.kubastion.terminal;

import java.util.regex.Pattern;

/** Cleans text coming out of a real terminal before anything tries to parse it. */
public final class TerminalText {

    /** ANSI sequences: colours, cursor moves, bracketed paste, OSC titles. */
    private static final Pattern ANSI = Pattern.compile(
            "\\[[0-?]*[ -/]*[@-~]"        // CSI
                    + "|\\][^]*(?:|\\\\)"  // OSC
                    + "|[@-Z\\\\-_]");    // two-character sequences

    private TerminalText() {
    }

    /**
     * Strips ANSI sequences and carriage returns. A PTY always injects them,
     * and a single stray \r is enough to make JSON parsing fail.
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return ANSI.matcher(raw).replaceAll("").replace("\r", "");
    }
}
