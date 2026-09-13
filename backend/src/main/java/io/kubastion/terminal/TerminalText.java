package io.kubastion.terminal;

import java.util.regex.Pattern;

/** Pulizia del testo che esce da un terminale vero, prima di provare a parsarlo. */
public final class TerminalText {

    /** Sequenze ANSI: colori, movimenti cursore, bracketed paste, titoli OSC. */
    private static final Pattern ANSI = Pattern.compile(
            "\\[[0-?]*[ -/]*[@-~]"        // CSI
                    + "|\\][^]*(?:|\\\\)"  // OSC
                    + "|[@-Z\\\\-_]");    // sequenze a due caratteri

    private TerminalText() {
    }

    /**
     * Toglie sequenze ANSI e ritorni carrello. Un PTY li inserisce sempre, e
     * basta un \r di troppo per far fallire il parsing del JSON.
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return ANSI.matcher(raw).replaceAll("").replace("\r", "");
    }
}
