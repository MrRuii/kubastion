package io.kubastion.pods;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.terminal.TerminalText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Trasforma l'output grezzo catturato dal terminale in una lista di pod.
 *
 * Sta fuori dal servizio perche' e' il punto piu' esposto del progetto: cio'
 * che arriva non e' un JSON pulito da una API, ma testo uscito da uno
 * pseudo-terminale, con sequenze ANSI, ritorni carrello e — quando qualcosa va
 * storto — messaggi d'errore di kubectl al posto dei dati.
 */
public final class PodListParser {

    private PodListParser() {
    }

    /** Esito del parsing: o i pod, o un motivo leggibile per cui non ci sono. */
    public sealed interface Result {

        /** Lista di pod, eventualmente vuota (namespace senza pod e' un successo). */
        record Pods(List<PodView> pods) implements Result {
        }

        /** Il comando non ha prodotto dati interpretabili. */
        record Failure(String message) implements Result {
        }
    }

    public static Result parse(String rawPayload, ObjectMapper mapper) {
        String text = TerminalText.clean(rawPayload).trim();

        if (text.isEmpty()) {
            return new Result.Failure("Nessun output da kubectl: sei collegato alla macchina giusta?");
        }
        // kubectl non produce JSON quando il namespace e' vuoto
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
            return new Result.Failure("Output di kubectl non interpretabile: " + firstLine(text));
        }
    }

    static String firstLine(String text) {
        return text.lines().filter(line -> !line.isBlank()).findFirst().orElse(text);
    }
}
