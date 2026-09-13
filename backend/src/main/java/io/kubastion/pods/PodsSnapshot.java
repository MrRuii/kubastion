package io.kubastion.pods;

import java.util.List;

/**
 * Stato completo inviato alla UI a ogni cambiamento.
 *
 * Si manda sempre la lista intera invece dei delta: con qualche decina di pod
 * costa nulla ed elimina un'intera categoria di bug di sincronizzazione.
 * Se un giorno i pod saranno migliaia, questo e' il punto da cambiare.
 */
public record PodsSnapshot(
        Connection connection,
        String message,
        String namespace,
        List<PodView> pods,
        long updatedAt) {

    public enum Connection {
        /** Stream aperto, i dati sono live. */
        CONNECTED,
        /** Stream caduto: di solito la credenziale e' scaduta. Riprova da solo. */
        RECONNECTING,
        /** Non ancora partito. */
        STARTING
    }
}
