package io.kubastion.pods;

import java.util.List;

/**
 * Monitoring state pushed to the UI.
 *
 * The whole list is sent every time: with a few dozen pods it costs nothing and
 * it removes an entire class of synchronisation bugs.
 */
public record PodsSnapshot(
        State state,
        String message,
        String namespace,
        List<PodView> pods,
        long updatedAt) {

    public enum State {
        /** Monitoring off: you are just using the terminal. */
        IDLE,
        /** Polling is on and the last round succeeded. */
        MONITORING,
        /** Polling is on but the last command failed; the reason is in message. */
        ERROR
    }

    public static PodsSnapshot idle(String namespace) {
        return new PodsSnapshot(State.IDLE, "", namespace, List.of(), System.currentTimeMillis());
    }
}
