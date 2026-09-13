package io.kubastion.pods;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La proiezione di un pod ha piu' rami di quanti sembrino: la fase da sola non
 * basta mai a dire se qualcosa e' rotto. Questi test fissano le precedenze.
 */
class PodViewTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode pod(String json) throws JsonProcessingException {
        return mapper.readTree(json);
    }

    @Test
    void podSanoEInSalute() throws Exception {
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
    void ilMotivoDelContainerBatteLaFase() throws Exception {
        // La fase dice "Running", ma un container e' in CrashLoopBackOff:
        // mostrare "Running" qui sarebbe il bug piu' dannoso di tutti.
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
    void iRestartSiSommanoSuTuttiIContainer() throws Exception {
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
    void containerCreatingNonEUnProblema() throws Exception {
        // Un pod che sta partendo non va segnalato come guasto: e' rumore che
        // renderebbe la tabella inutile a ogni deploy.
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
    void terminatedCompletedNonEUnProblemaEIlJobEInSalute() throws Exception {
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
    void terminatedConErroreEUnProblema() throws Exception {
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
    void laCancellazioneBatteQualunqueAltroStato() throws Exception {
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
    void senzaContainerStatusesLaColonnaReadyNonMente() throws Exception {
        // Un pod appena schedulato non ha ancora containerStatuses: "0/0"
        // sembrerebbe un guasto, quindi si mostra "-".
        PodView view = PodView.from(pod("""
                {"metadata": {"name": "fresh"}, "status": {"phase": "Pending"}}
                """));

        assertEquals("-", view.ready());
        assertFalse(view.healthy());
    }

    @Test
    void faseMancanteDiventaUnknown() throws Exception {
        PodView view = PodView.from(pod("""
                {"metadata": {"name": "mystery"}, "status": {}}
                """));

        assertEquals("Unknown", view.status());
        assertFalse(view.healthy());
    }

    @Test
    void senzaStartTimeSiUsaLaCreazione() throws Exception {
        PodView view = PodView.from(pod("""
                {
                  "metadata": {"name": "no-start", "creationTimestamp": "2026-09-12T10:00:00Z"},
                  "status": {"phase": "Pending"}
                }
                """));

        assertEquals("2026-09-12T10:00:00Z", view.startedAt());
    }

    @Test
    void jsonQuasiVuotoNonFaEsplodereNulla() throws Exception {
        PodView view = PodView.from(pod("{}"));

        assertEquals("", view.name());
        assertEquals("Unknown", view.status());
        assertEquals("-", view.ready());
        assertEquals(0, view.restarts());
        assertFalse(view.healthy());
    }
}
