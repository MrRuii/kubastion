package io.kubastion.pods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.terminal.TerminalText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the raw text captured from the terminal into a list of pods.
 *
 * This lives outside the service because it is the most exposed part of the
 * project: what arrives is not clean JSON from an API, but text that came out
 * of a pseudo-terminal — ANSI sequences, carriage returns, and, when something
 * goes wrong, a kubectl error message where the data should be.
 */
public final class PodListParser {

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

    public static Result parse(String rawPayload, ObjectMapper mapper) {
        String text = TerminalText.clean(rawPayload).trim();

        if (text.isEmpty()) {
            return new Result.Failure("No output from kubectl. Are you connected to the right machine?");
        }
        // kubectl prints no JSON at all when the namespace is empty
        if (text.contains("No resources found")) {
            return new Result.Pods(List.of());
        }

        int brace = text.indexOf('{');
        if (brace < 0) {
            return new Result.Failure(firstLine(text));
        }
        try {
            JsonNode root = mapper.readTree(text.substring(brace));
            JsonNode items = root.path("items");
            if (!items.isArray()) {
                return new Result.Failure(firstLine(text));
            }
            List<PodView> pods = new ArrayList<>();
            for (JsonNode item : items) {
                pods.add(PodView.from(item));
            }
            pods.sort(Comparator.comparing(PodView::name));
            return new Result.Pods(List.copyOf(pods));
        } catch (Exception e) {
            return new Result.Failure("Could not interpret kubectl output: " + firstLine(text));
        }
    }

    static String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse(text);
    }
}
