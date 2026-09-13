package io.kubastion.pods;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parser receives text that came out of a pseudo-terminal, not a clean HTTP
 * response: ANSI sequences, carriage returns, lines wrapped at the window width,
 * and every so often a kubectl error where the data should be. Those are the
 * cases that matter.
 */
class PodListParserTest {

    /** name|ns|phase|deletionTs|node|startTs|creationTs|containers@@ */
    private static String record(String name, String phase, String deletion, String containers) {
        return name + "|demo|" + phase + "|" + deletion + "|node-01|"
                + "2026-09-13T01:00:00Z|2026-09-13T01:00:00Z|" + containers + "@@";
    }

    private static final String RUNNING = record("zeta", "Running", "", "true,0,,;")
            + record("alpha", "Running", "", "true,0,,;");

    private List<PodView> pods(String payload) {
        PodListParser.Result result = PodListParser.parse(payload);
        assertInstanceOf(PodListParser.Result.Pods.class, result,
                () -> "expected success, got: " + result);
        return ((PodListParser.Result.Pods) result).pods();
    }

    private String failure(String payload) {
        PodListParser.Result result = PodListParser.parse(payload);
        assertInstanceOf(PodListParser.Result.Failure.class, result,
                () -> "expected failure, got: " + result);
        return ((PodListParser.Result.Failure) result).message();
    }

    @Test
    void recordsAreSortedByName() {
        List<PodView> pods = pods(RUNNING);

        assertEquals(2, pods.size());
        assertEquals("alpha", pods.get(0).name());
        assertEquals("zeta", pods.get(1).name());
    }

    @Test
    void everyFieldSurvivesTheTrip() {
        PodView pod = pods(record("billing", "Running", "", "true,2,,;false,7,CrashLoopBackOff,;")).get(0);

        assertEquals("billing", pod.name());
        assertEquals("demo", pod.namespace());
        assertEquals("CrashLoopBackOff", pod.status());
        assertEquals("1/2", pod.ready());
        assertEquals(9, pod.restarts());
        assertEquals("node-01", pod.node());
        assertFalse(pod.healthy());
    }

    @Test
    void aLineWrappedThroughTheMiddleOfATokenStillParses() {
        // This is the whole reason records end in @@ instead of a newline: the
        // terminal wraps at its width, wherever that lands.
        String wrapped = "billing|demo|Run\nning||node-01|2026-09-13T01:00:00Z|"
                + "2026-09-13T01:00:00Z|true,0,,;@@";

        List<PodView> pods = pods(wrapped);

        assertEquals(1, pods.size());
        assertEquals("Running", pods.get(0).status());
    }

    @Test
    void carriageReturnsAndAnsiAreStripped() {
        String noisy = "[0m[32m" + RUNNING.replace("@@", "@@\r\n") + "[0m";

        assertEquals(2, pods(noisy).size());
    }

    @Test
    void terminatingBeatsThePhase() {
        PodView pod = pods(record("dying", "Running", "2026-09-13T01:00:00Z", "true,0,,;")).get(0);

        assertEquals("Terminating", pod.status());
        assertFalse(pod.healthy());
    }

    @Test
    void aPodWithNoContainersYetDoesNotShowAMisleadingReady() {
        PodView pod = pods(record("fresh", "Pending", "", "")).get(0);

        assertEquals("-", pod.ready());
        assertEquals("Pending", pod.status());
    }

    @Test
    void emptyOutputIsAnEmptyNamespaceNotAFailure() {
        // kubectl's jsonpath prints nothing at all when there is nothing.
        assertTrue(pods("").isEmpty());
        assertTrue(pods("   \r\n  ").isEmpty());
    }

    @Test
    void noResourcesFoundIsSuccess() {
        assertTrue(pods("No resources found in demo namespace.").isEmpty());
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
    void aTruncatedRecordIsAFailureNotHalfATable() {
        // Better to say the output was unreadable than to show a pod built from
        // fields that never arrived.
        String message = failure("billing|demo|Running@@");

        assertTrue(message.startsWith("Could not interpret"), message);
    }

    @Test
    void aTrailingPartialRecordWithoutItsMarkerIsIgnored() {
        // The last record always ends with @@; anything after it is noise.
        List<PodView> pods = pods(RUNNING + "mike@jump:~$ ");

        assertEquals(2, pods.size());
    }
}
