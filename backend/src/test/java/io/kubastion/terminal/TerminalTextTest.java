package io.kubastion.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Un PTY infila sequenze di controllo ovunque. Basta un \r di troppo perche' il
 * JSON non si parsi piu': questa pulizia sta prima di tutto il resto.
 */
class TerminalTextTest {

    @Test
    void toglieIColori() {
        assertEquals("ciao", TerminalText.clean("[32mciao[0m"));
    }

    @Test
    void toglieIMovimentiDelCursore() {
        assertEquals("testo", TerminalText.clean("[2J[H testo".replace(" ", "")));
    }

    @Test
    void toglieIlTitoloDellaFinestra() {
        // Molti prompt aggiornano il titolo del terminale a ogni comando.
        assertEquals("dopo", TerminalText.clean("]0;utente@host: ~dopo"));
    }

    @Test
    void toglieIRitorniCarrelloMaTieneLeRigheNuove() {
        assertEquals("a\nb", TerminalText.clean("a\r\nb"));
    }

    @Test
    void testoNormaleRestaIntatto() {
        String json = "{\"items\": [], \"kind\": \"List\"}";

        assertEquals(json, TerminalText.clean(json));
    }

    @Test
    void gestisceNullEVuoto() {
        assertEquals("", TerminalText.clean(null));
        assertEquals("", TerminalText.clean(""));
    }

    @Test
    void nonMangiaLeParentesiGraffeDelJson() {
        // Verifica che la regex ANSI non sia troppo golosa su caratteri comuni.
        String payload = "[0m{\"a\":[1,2]}[K";

        assertEquals("{\"a\":[1,2]}", TerminalText.clean(payload));
    }
}
