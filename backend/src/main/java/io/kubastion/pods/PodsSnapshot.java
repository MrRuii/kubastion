package io.kubastion.pods;

import java.util.List;

/**
 * Stato del monitoraggio inviato alla UI.
 *
 * Si manda sempre la lista intera: con qualche decina di pod costa nulla ed
 * elimina un'intera categoria di bug di sincronizzazione.
 */
public record PodsSnapshot(
        State state,
        String message,
        String namespace,
        List<PodView> pods,
        long updatedAt) {

    public enum State {
        /** Monitoraggio spento: stai usando il terminale e basta. */
        IDLE,
        /** Polling attivo, l'ultimo giro e' andato bene. */
        MONITORING,
        /** Polling attivo ma l'ultimo comando e' fallito: il motivo e' in message. */
        ERROR
    }

    public static PodsSnapshot idle(String namespace) {
        return new PodsSnapshot(State.IDLE, "", namespace, List.of(), System.currentTimeMillis());
    }
}
