package io.kubastion.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A PTY sprinkles control sequences everywhere. One stray \r is enough for the
 * JSON to stop parsing, so this cleanup runs before everything else.
 */
class TerminalTextTest {

    @Test
    void stripsColours() {
        assertEquals("hello", TerminalText.clean("[32mhello[0m"));
    }

    @Test
    void stripsCursorMovement() {
        assertEquals("text", TerminalText.clean("[2J[Htext"));
    }

    @Test
    void stripsTheWindowTitleSequence() {
        // Plenty of prompts rewrite the terminal title on every command.
        assertEquals("after", TerminalText.clean("]0;user@host: ~after"));
    }

    @Test
    void dropsCarriageReturnsButKeepsNewlines() {
        assertEquals("a\nb", TerminalText.clean("a\r\nb"));
    }

    @Test
    void plainTextIsLeftAlone() {
        String json = "{\"items\": [], \"kind\": \"List\"}";

        assertEquals(json, TerminalText.clean(json));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", TerminalText.clean(null));
        assertEquals("", TerminalText.clean(""));
    }

    @Test
    void doesNotEatJsonBraces() {
        // Guards against an ANSI regex that is too greedy on common characters.
        String payload = "[0m{\"a\":[1,2]}[K";

        assertEquals("{\"a\":[1,2]}", TerminalText.clean(payload));
    }
}
