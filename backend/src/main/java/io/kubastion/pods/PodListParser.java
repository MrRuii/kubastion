package io.kubastion.pods;

import io.kubastion.terminal.TerminalText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the raw text captured from the terminal into a list of pods.
 *
 * This lives outside the service because it is the most exposed part of the
 * project: what arrives is not a clean HTTP response, but text that came out of
 * a pseudo-terminal — ANSI sequences, carriage returns, lines wrapped at the
 * window width, and, when something goes wrong, a kubectl error message where
 * the data should be.
 *
 * The format it reads is the compact one built in {@code KubectlCommands}: one
 * record per pod ended by {@code @@}, eight fields separated by {@code |}, the
 * last of which lists the containers. Because records are not newline-delimited,
 * every line break can be discarded first — which is exactly what makes the
 * parse immune to the terminal wrapping long lines.
 */
public final class PodListParser {

    private static final String RECORD_END = "@@";
    private static final int FIELDS = 8;

    private PodListParser() {
    }

    /** Either the pods, or a readable reason why there are none. */
    public sealed interface Result {

        /** Pod list, possibly empty — an empty namespace is a success. */
        record Pods(List<PodView> pods) implements Result {
        }

        /** The command produced nothing we could interpret. */
        record Failure(String message) implements Result {
        }
    }

    public static Result parse(String rawPayload) {
        String cleaned = TerminalText.clean(rawPayload);
        // Drop every line break before anything else: a wrap lands wherever the
        // character count says, including mid-token, and records are delimited
        // by @@ rather than by newlines precisely so this is safe.
        String text = cleaned.replace("\n", "").replace("\r", "").trim();

        if (text.isEmpty()) {
            // kubectl prints nothing at all for an empty namespace.
            return new Result.Pods(List.of());
        }
        if (cleaned.contains("No resources found")) {
            return new Result.Pods(List.of());
        }
        if (!text.contains(RECORD_END)) {
            // No record markers: whatever came back is a message, not data —
            // most often a kubectl error worth showing verbatim.
            return new Result.Failure(firstLine(cleaned));
        }

        String[] chunks = text.split(java.util.regex.Pattern.quote(RECORD_END));
        // Anything after the final @@ was never a record — a prompt the terminal
        // redrew, say. A chunk that @@ *did* terminate has to be whole, though:
        // half a record must fail rather than become half a row.
        int complete = text.endsWith(RECORD_END) ? chunks.length : chunks.length - 1;

        List<PodView> pods = new ArrayList<>();
        for (int i = 0; i < complete; i++) {
            if (chunks[i].isBlank()) {
                continue;
            }
            PodView pod = toPod(chunks[i]);
            if (pod == null) {
                return new Result.Failure("Could not interpret kubectl output: " + firstLine(cleaned));
            }
            pods.add(pod);
        }
        pods.sort(Comparator.comparing(PodView::name));
        return new Result.Pods(List.copyOf(pods));
    }

    /** One {@code name|ns|phase|deletionTs|node|startTs|creationTs|containers} record. */
    private static PodView toPod(String record) {
        String[] fields = record.split("\\|", -1);
        if (fields.length < FIELDS) {
            return null;
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            return null;
        }
        String startedAt = fields[5].trim().isEmpty() ? fields[6].trim() : fields[5].trim();

        return PodView.of(
                name,
                fields[1].trim(),
                fields[2].trim(),
                !fields[3].trim().isEmpty(),
                fields[4].trim(),
                startedAt,
                containersOf(fields[7]));
    }

    /** {@code ready,restarts,waitingReason,terminatedReason;} per container. */
    private static List<PodView.Container> containersOf(String blob) {
        List<PodView.Container> containers = new ArrayList<>();
        for (String entry : blob.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(",", -1);
            if (parts.length < 4) {
                continue;
            }
            containers.add(new PodView.Container(
                    "true".equalsIgnoreCase(parts[0].trim()),
                    parseCount(parts[1]),
                    parts[2].trim(),
                    parts[3].trim()));
        }
        return containers;
    }

    private static int parseCount(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse(text).trim();
    }
}
