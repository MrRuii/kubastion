package io.kubastion.pods;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

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

    /** One container's state, however the fields were transported. */
    public record Container(boolean ready, int restarts, String waitingReason, String terminatedReason) {
    }

    public static PodView from(JsonNode pod) {
        JsonNode metadata = pod.path("metadata");
        JsonNode spec = pod.path("spec");
        JsonNode status = pod.path("status");

        List<Container> containers = new ArrayList<>();
        for (JsonNode container : status.path("containerStatuses")) {
            JsonNode state = container.path("state");
            containers.add(new Container(
                    container.path("ready").asBoolean(false),
                    container.path("restartCount").asInt(0),
                    state.path("waiting").path("reason").asText(""),
                    state.path("terminated").path("reason").asText("")));
        }

        return of(
                metadata.path("name").asText(""),
                // The pod tells us its own namespace — the cheapest way to learn
                // which namespace we are watching when none was configured.
                metadata.path("namespace").asText(""),
                status.path("phase").asText(""),
                metadata.hasNonNull("deletionTimestamp"),
                spec.path("nodeName").asText(""),
                status.path("startTime").asText(metadata.path("creationTimestamp").asText("")),
                containers);
    }

    /**
     * The single place the status rules live, whatever format carried the
     * fields here — the verbose JSON or the compact one-line-per-pod form.
     */
    public static PodView of(String name, String namespace, String phase, boolean terminating,
                             String node, String startedAt, List<Container> containers) {
        int ready = 0;
        int total = 0;
        int restarts = 0;
        String problem = "";

        for (Container container : containers) {
            total++;
            if (container.ready()) {
                ready++;
            }
            restarts += container.restarts();
            if (problem.isEmpty()) {
                problem = problemOf(container);
            }
        }

        // Precedence: being deleted beats everything, then the container's
        // blocking reason (CrashLoopBackOff, ImagePullBackOff…), then the phase.
        String display;
        if (terminating) {
            display = "Terminating";
        } else if (!problem.isEmpty()) {
            display = problem;
        } else {
            display = phase.isEmpty() ? "Unknown" : phase;
        }

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
                node,
                startedAt,
                healthy);
    }

    /** Why a container is not running, if that is the case. */
    private static String problemOf(Container container) {
        String waiting = container.waitingReason();
        if (!waiting.isEmpty() && !"ContainerCreating".equals(waiting) && !"PodInitializing".equals(waiting)) {
            return waiting;
        }
        String terminated = container.terminatedReason();
        if (!terminated.isEmpty() && !"Completed".equals(terminated)) {
            return terminated;
        }
        return "";
    }
}
