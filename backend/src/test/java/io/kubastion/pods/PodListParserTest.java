package io.kubastion.pods;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser receives text that came out of a pseudo-terminal, not a clean HTTP
 * response: there can be noise before and after the JSON, ANSI sequences, and
 * every so often a kubectl error where the data should be. Those are the cases
 * that matter.
 */
class PodListParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String VALID_LIST = """
            {
              "apiVersion": "v1",
              "kind": "List",
              "items": [
                {"metadata": {"name": "zeta"}, "status": {"phase": "Running",
                  "containerStatuses": [{"ready": true, "restartCount": 0, "state": {"running": {}}}]}},
                {"metadata": {"name": "alpha"}, "status": {"phase": "Running",
                  "containerStatuses": [{"ready": true, "restartCount": 0, "state": {"running": {}}}]}}
              ]
            }
            """;

    private List<PodView> pods(String payload) {
        PodListParser.Result result = PodListParser.parse(payload, mapper);
        assertInstanceOf(PodListParser.Result.Pods.class, result,
                () -> "expected success, got: " + result);
        return ((PodListParser.Result.Pods) result).pods();
    }

    private String failure(String payload) {
        PodListParser.Result result = PodListParser.parse(payload, mapper);
        assertInstanceOf(PodListParser.Result.Failure.class, result,
                () -> "expected failure, got: " + result);
        return ((PodListParser.Result.Failure) result).message();
    }

    @Test
    void validListIsSortedByName() {
        List<PodView> pods = pods(VALID_LIST);

        assertEquals(2, pods.size());
        assertEquals("alpha", pods.get(0).name());
        assertEquals("zeta", pods.get(1).name());
    }

    @Test
    void noiseBeforeTheJsonIsIgnored() {
        // The command echo and the prompt always come before the real output.
        String payload = "$ kubectl get pods -o json\n" + VALID_LIST;

        assertEquals(2, pods(payload).size());
    }

    @Test
    void thePromptAfterTheJsonDoesNotBreakParsing() {
        // The nastiest case: the terminal reprints the prompt right after.
        String payload = VALID_LIST + "\nmike@jump:~$ ";

        assertEquals(2, pods(payload).size());
    }

    @Test
    void ansiSequencesAndCarriageReturnsAreStripped() {
        String payload = "[0m[32m\r\n" + VALID_LIST.replace("\n", "\r\n") + "[0m";

        assertEquals(2, pods(payload).size());
    }

    @Test
    void aTokenSplitByTheTerminalWrapIsPutBackTogether() {
        // The window was narrower than the line, so the terminal wrapped it in
        // the middle of a word. This broke every poll on a small screen.
        String wrapped = """
                {"items": [
                  {"metadata": {"name": "billing"}, "status": {"phase": "Running",
                    "containerStatuses": [{"ready": false, "restartCount": 1,
                      "state": {"waiting": {"reason": "CrashLoopBack
                Off"}}}]}}
                ]}
                """;

        List<PodView> pods = pods(wrapped);

        assertEquals(1, pods.size());
        assertEquals("CrashLoopBackOff", pods.get(0).status());
    }

    @Test
    void emptyNamespaceIsSuccessNotFailure() {
        // kubectl prints no JSON when there is nothing: still a healthy state.
        assertTrue(pods("No resources found in demo namespace.").isEmpty());
    }

    @Test
    void listWithEmptyItemsIsSuccess() {
        assertTrue(pods("{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[]}").isEmpty());
    }

    @Test
    void emptyOutputSaysWhatToCheck() {
        String message = failure("   \n  ");

        assertTrue(message.contains("connected"), () -> "unhelpful message: " + message);
    }

    @Test
    void kubectlErrorIsReportedVerbatim() {
        String message = failure("error: You must be logged in to the server (Unauthorized)");

        assertEquals("error: You must be logged in to the server (Unauthorized)", message);
    }

    @Test
    void commandNotFoundIsReported() {
        String message = failure("bash: kubectl: command not found");

        assertEquals("bash: kubectl: command not found", message);
    }

    @Test
    void onlyTheFirstLineOfAnErrorReachesTheUi() {
        String message = failure("""
                error: unable to connect
                a very long detail nobody needs
                another line
                """);

        assertEquals("error: unable to connect", message);
    }

    @Test
    void malformedJsonExplainsInsteadOfCrashing() {
        String message = failure("{ this is not valid json ");

        assertTrue(message.contains("Could not interpret"), () -> "message: " + message);
    }

    @Test
    void validJsonWithoutItemsIsAFailure() {
        // For instance a single Pod instead of a List: better to say so than to
        // show an empty table implying there is nothing there.
        String message = failure("{\"kind\":\"Pod\",\"metadata\":{\"name\":\"lonely\"}}");

        assertTrue(message.startsWith("{"), () -> "message: " + message);
    }
}
