package io.kubastion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Tutta la configurazione dell'applicazione. Nessun valore sensibile vive qui:
 * la chiave SSH non passa mai da kubastion, la usa il binario ssh di sistema
 * tramite l'agent e il tuo ~/.ssh/config.
 */
@ConfigurationProperties(prefix = "kubastion")
public record KubastionProperties(
        Ssh ssh,
        Kubectl kubectl,
        Logs logs,
        Watch watch) {

    /**
     * @param destination destinazione ssh, esattamente come la scriveresti a mano
     *                    (idealmente un alias definito in ~/.ssh/config)
     * @param options     argomenti extra passati al binario ssh
     */
    public record Ssh(String destination, List<String> options) {
    }

    /**
     * @param binary    percorso di kubectl SUL JUMP HOST
     * @param namespace namespace osservato
     * @param context   contesto kubectl opzionale
     */
    public record Kubectl(String binary, String namespace, String context) {
    }

    public record Logs(int tailLines) {
    }

    /** @param retryDelaySeconds attesa prima di riaprire lo stream dopo una caduta */
    public record Watch(int retryDelaySeconds) {
    }
}
