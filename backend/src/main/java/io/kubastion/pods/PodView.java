package io.kubastion.pods;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A pod as the UI needs it: only the fields that tell you at a glance whether
 * something is broken. No full YAML.
 */
public record PodView(
        String name,
        String namespace,
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
        // The pod tells us its own namespace — the cheapest way to learn which
        // namespace we are actually watching when none was configured.
        String namespace = metadata.path("namespace").asText("");
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

        // Precedence: being deleted beats everything, then the container's
        // blocking reason (CrashLoopBackOff, ImagePullBackOff…), then the phase.
        boolean terminating = metadata.hasNonNull("deletionTimestamp");
        String display;
        if (terminating) {
            display = "Terminating";
        } else if (!problem.isEmpty()) {
            display = problem;
        } else {
            display = phase.isEmpty() ? "Unknown" : phase;
        }

        String startedAt = status.path("startTime").asText(metadata.path("creationTimestamp").asText(""));
        // healthy drives the colour in the table, so it must always agree with
        // the status shown — otherwise a "Terminating" pod would render green.
        boolean healthy = !terminating
                && (("Running".equals(phase) && total > 0 && ready == total && problem.isEmpty())
                || "Succeeded".equals(phase));

        return new PodView(
                name,
                namespace,
                display,
                total == 0 ? "-" : ready + "/" + total,
                restarts,
                spec.path("nodeName").asText(""),
                startedAt,
                healthy);
    }

    /** Why a container is not running, if that is the case. */
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
