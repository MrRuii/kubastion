package io.kubastion.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting this wrong has two very different costs. Too eager, and the poller
 * writes into a line you were typing. Too cautious, and the table freezes for
 * good because we think you are typing when you are not.
 */
class InputTrackerTest {

    private final InputTracker tracker = new InputTracker();

    private void type(String data) {
        tracker.record(data, System.currentTimeMillis());
    }

    @Test
    void nothingTypedMeansNothingPending() {
        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void aHalfTypedCommandIsPending() {
        type("kubectl get pods -n ");

        assertTrue(tracker.hasPendingLine());
    }

    @Test
    void enterSubmitsTheLine() {
        type("kubectl get pods");
        type("\r");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void backspacingBackToAnEmptyPromptClearsIt() {
        type("ls");
        type("");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void backspaceOnAnEmptyLineDoesNotGoNegative() {
        // Otherwise a few stray backspaces would make a real line look empty.
        type("");
        type("x");

        assertTrue(tracker.hasPendingLine());
    }

    @Test
    void ctrlCAbandonsTheLine() {
        type("rm -rf /tmp/whatever");
        type("");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void ctrlUKillsTheLine() {
        type("a mistake");
        type("");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void arrowKeysAreNotCharactersOnTheLine() {
        // An arrow key is ESC [ A. Counting the final letter would leave the
        // line looking busy forever, and polling would never resume.
        type("[A[B[C[D");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void homeAndDeleteSequencesEndingInTildeAreIgnoredToo() {
        type("[1~[3~");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void anEscapeSequenceDoesNotSwallowWhatComesAfterIt() {
        type("[Als");

        assertTrue(tracker.hasPendingLine());
    }

    @Test
    void tabAndOtherControlCharactersDoNotCount() {
        type("\t");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void aWholeSessionPastedAtOnceEndsAtAnEmptyPrompt() {
        type("ssh jump-host\r1\rkubectl get pods\r");

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void aPasteEndingMidCommandIsStillPending() {
        type("ssh jump-host\rkubectl get po");

        assertTrue(tracker.hasPendingLine());
    }

    @Test
    void aNewShellForgetsWhatWasTyped() {
        type("half a command");
        tracker.reset();

        assertFalse(tracker.hasPendingLine());
    }

    @Test
    void emptyInputChangesNothing() {
        type("ls");
        type("");
        type(null);

        assertTrue(tracker.hasPendingLine());
    }
}
