package io.kubastion.pods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Projecting a pod has more branches than it looks: the phase alone never tells
 * you whether something is broken. These tests pin down the precedence rules.
 */
class PodViewTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode pod(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    @Test
    void healthyRunningPod() throws Exception {
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "api-gateway-1"},
                  "spec": {"nodeName": "node-01"},
                  "status": {"phase": "Running", "startTime": "2026-09-13T01:00:00Z",
                    "containerStatuses": [{"ready": true, "restartCount": 0, "state": {"running": {}}}]}
                }
                """));

        assertEquals("api-gateway-1", view.name());
        assertEquals("Running", view.status());
        assertEquals("1/1", view.ready());
        assertEquals(0, view.restarts());
        assertEquals("node-01", view.node());
        assertTrue(view.healthy());
    }

    @Test
    void containerReasonBeatsPhase() throws Exception {
        // The phase says "Running" while a container is in CrashLoopBackOff.
        // Showing "Running" here would be the most damaging bug of all.
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "billing"},
                  "spec": {"nodeName": "node-02"},
                  "status": {"phase": "Running",
                    "containerStatuses": [
                      {"ready": true,  "restartCount": 2, "state": {"running": {}}},
                      {"ready": false, "restartCount": 7,
                       "state": {"waiting": {"reason": "CrashLoopBackOff"}}}]}
                }
                """));

        assertEquals("CrashLoopBackOff", view.status());
        assertEquals("1/2", view.ready());
        assertFalse(view.healthy());
    }

    @Test
    void restartsAreSummedAcrossContainers() throws Exception {
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "multi"},
                  "status": {"phase": "Running",
                    "containerStatuses": [
                      {"ready": true, "restartCount": 3, "state": {"running": {}}},
                      {"ready": true, "restartCount": 4, "state": {"running": {}}},
                      {"ready": true, "restartCount": 5, "state": {"running": {}}}]}
                }
                """));

        assertEquals(12, view.restarts());
        assertEquals("3/3", view.ready());
        assertTrue(view.healthy());
    }

    @Test
    void containerCreatingIsNotAProblem() throws Exception {
        // A pod that is starting up must not be flagged as broken: that noise
        // would make the table useless on every deployment.
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "starting"},
                  "status": {"phase": "Pending",
                    "containerStatuses": [{"ready": false, "restartCount": 0,
                      "state": {"waiting": {"reason": "ContainerCreating"}}}]}
                }
                """));

        assertEquals("Pending", view.status());
        assertFalse(view.healthy());
    }

    @Test
    void completedJobIsHealthy() throws Exception {
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "job-1"},
                  "status": {"phase": "Succeeded",
                    "containerStatuses": [{"ready": false, "restartCount": 0,
                      "state": {"terminated": {"reason": "Completed"}}}]}
                }
                """));

        assertEquals("Succeeded", view.status());
        assertTrue(view.healthy());
    }

    @Test
    void terminationWithErrorIsAProblem() throws Exception {
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "failed-1"},
                  "status": {"phase": "Failed",
                    "containerStatuses": [{"ready": false, "restartCount": 1,
                      "state": {"terminated": {"reason": "OOMKilled"}}}]}
                }
                """));

        assertEquals("OOMKilled", view.status());
        assertFalse(view.healthy());
    }

    @Test
    void deletionBeatsEveryOtherState() throws Exception {
        // Status and colour must agree: a pod on its way out is never green.
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "dying", "deletionTimestamp": "2026-09-13T01:00:00Z"},
                  "status": {"phase": "Running",
                    "containerStatuses": [{"ready": true, "restartCount": 0, "state": {"running": {}}}]}
                }
                """));

        assertEquals("Terminating", view.status());
        assertFalse(view.healthy());
    }

    @Test
    void withoutContainerStatusesTheReadyColumnDoesNotLie() throws Exception {
        // A freshly scheduled pod has no containerStatuses yet: "0/0" would
        // look like a failure, so we show "-" instead.
        PodView view = PodView.from(pod("""
                {"metadata": {"name": "fresh"}, "status": {"phase": "Pending"}}
                """));

        assertEquals("-", view.ready());
        assertFalse(view.healthy());
    }

    @Test
    void missingPhaseBecomesUnknown() throws Exception {
        PodView view = PodView.from(pod("""
                {"metadata": {"name": "mystery"}, "status": {}}
                """));

        assertEquals("Unknown", view.status());
        assertFalse(view.healthy());
    }

    @Test
    void fallsBackToCreationTimestamp() throws Exception {
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "no-start", "creationTimestamp": "2026-09-12T10:00:00Z"},
                  "status": {"phase": "Pending"}
                }
                """));

        assertEquals("2026-09-12T10:00:00Z", view.startedAt());
    }

    @Test
    void almostEmptyJsonDoesNotBlowUp() throws Exception {
        PodView view = PodView.from(pod("{}"));

        assertEquals("", view.name());
        assertEquals("Unknown", view.status());
        assertEquals("-", view.ready());
        assertEquals(0, view.restarts());
        assertFalse(view.healthy());
    }
}
