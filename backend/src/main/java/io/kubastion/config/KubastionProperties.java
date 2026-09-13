package io.kubastion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configurazione dell'applicazione.
 *
 * Nessun valore sensibile vive qui: kubastion non conosce chiavi, password o
 * host remoti. Apre una shell locale e sei tu, dentro il terminale, a fare il
 * login come lo faresti normalmente.
 */
@ConfigurationProperties(prefix = "kubastion")
public record KubastionProperties(
        Terminal terminal,
        Kubectl kubectl,
        Monitor monitor) {

    /**
     * La shell locale aperta nel terminale del browser. Da li' lanci tu
     * ssh-add, ssh, il menu del gateway e tutto il resto.
     *
     * @param command eseguibile della shell; vuoto = scelta automatica per OS
     * @param args    argomenti della shell
     */
    public record Terminal(String command, List<String> args) {
    }

    /**
     * @param binary    percorso di kubectl SULLA MACCHINA REMOTA
     * @param namespace namespace osservato
     * @param context   contesto kubectl opzionale
     */
    public record Kubectl(String binary, String namespace, String context) {
    }

    /**
     * @param intervalSeconds intervallo di polling di `kubectl get pods`
     * @param timeoutSeconds  oltre questo tempo il comando e' considerato perso
     *                        e il terminale torna visibile
     */
    public record Monitor(int intervalSeconds, int timeoutSeconds) {
    }
}
