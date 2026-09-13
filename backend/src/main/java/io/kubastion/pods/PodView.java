package io.kubastion.pods;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Proiezione di un pod verso la UI. Solo i campi che servono a capire in un
 * colpo d'occhio se qualcosa e' rotto: niente YAML integrale.
 */
public record PodView(
        String name,
        String status,
        String ready,
        int restarts,
        String node,
        String startedAt,
        boolean healthy) {

    public static PodView from(JsonNode pod) {
        JsonNode metadata = pod.path("metadata");
        JsonNode spec = pod.path("spec");
        JsonNode status = pod.path("status");

        String name = metadata.path("name").asText("");
        String phase = status.path("phase").asText("");

        int ready = 0;
        int total = 0;
        int restarts = 0;
        String problem = "";

        for (JsonNode container : status.path("containerStatuses")) {
            total++;
            if (container.path("ready").asBoolean(false)) {
                ready++;
            }
            restarts += container.path("restartCount").asInt(0);
            if (problem.isEmpty()) {
                problem = problemOf(container.path("state"));
            }
        }

        // Ordine di precedenza: in cancellazione batte tutto, poi il motivo di
        // blocco del container (CrashLoopBackOff, ImagePullBackOff...), poi la fase.
        String display;
        if (metadata.hasNonNull("deletionTimestamp")) {
            display = "Terminating";
        } else if (!problem.isEmpty()) {
            display = problem;
        } else {
            display = phase.isEmpty() ? "Unknown" : phase;
        }

        String startedAt = status.path("startTime").asText(metadata.path("creationTimestamp").asText(""));
        boolean healthy = ("Running".equals(phase) && total > 0 && ready == total && problem.isEmpty())
                || "Succeeded".equals(phase);

        return new PodView(
                name,
                display,
                total == 0 ? "-" : ready + "/" + total,
                restarts,
                spec.path("nodeName").asText(""),
                startedAt,
                healthy);
    }

    /** Estrae il motivo per cui un container non sta girando, se c'e'. */
    private static String problemOf(JsonNode state) {
        String waiting = state.path("waiting").path("reason").asText("");
        if (!waiting.isEmpty() && !"ContainerCreating".equals(waiting) && !"PodInitializing".equals(waiting)) {
            return waiting;
        }
        String terminated = state.path("terminated").path("reason").asText("");
        if (!terminated.isEmpty() && !"Completed".equals(terminated)) {
            return terminated;
        }
        return "";
    }
}
