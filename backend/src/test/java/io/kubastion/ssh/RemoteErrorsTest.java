package io.kubastion.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Il valore di questa classe non e' tecnico: e' che in UI compaia una frase che
 * dice cosa fare, invece di "Permission denied (publickey)".
 */
class RemoteErrorsTest {

    @Test
    void credenzialeScadutaDiceDiRicaricareLaChiave() {
        String message = RemoteErrors.explain("mike@jump: Permission denied (publickey).");

        assertTrue(message.toLowerCase().contains("credenziale"), message);
        assertTrue(message.contains("ssh-add"), message);
    }

    @Test
    void hostNonRisolvibileRimandaAllaVpn() {
        String message = RemoteErrors.explain("ssh: Could not resolve hostname jump: No such host");

        assertTrue(message.toUpperCase().contains("VPN"), message);
    }

    @Test
    void timeoutRimandaAllaVpn() {
        String message = RemoteErrors.explain("ssh: connect to host jump port 22: Connection timed out");

        assertTrue(message.toUpperCase().contains("VPN"), message);
    }

    @Test
    void kubectlMancanteIndicaLaConfigurazione() {
        String message = RemoteErrors.explain("bash: kubectl: command not found");

        assertTrue(message.contains("kubectl"), message);
    }

    @Test
    void clusterIrraggiungibileDistingueDalProblemaSsh() {
        // Qui ssh ha funzionato: e' kubectl che non parla col cluster.
        String message = RemoteErrors.explain("Unable to connect to the server: dial tcp: i/o timeout");

        assertTrue(message.contains("cluster"), message);
    }

    @Test
    void permessiInsufficientiSonoUnCasoASe() {
        String message = RemoteErrors.explain(
                "Error from server (Forbidden): pods is forbidden: cannot list resource \"pods\"");

        assertTrue(message.toLowerCase().contains("permessi"), message);
    }

    @Test
    void stderrVuotoDaComunqueUnaFraseSensata() {
        String message = RemoteErrors.explain("");

        assertTrue(message.toLowerCase().contains("tentativo"), message);
        assertEquals(message, RemoteErrors.explain(null));
    }

    @Test
    void erroreSconosciutoRiportaSoloLaPrimaRigaUtile() {
        String message = RemoteErrors.explain("""

                qualcosa di inatteso e' successo
                riga di dettaglio che non serve
                """);

        assertEquals("qualcosa di inatteso e' successo", message);
    }
}
