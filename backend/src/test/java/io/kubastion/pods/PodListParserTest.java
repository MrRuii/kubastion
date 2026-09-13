package io.kubastion.pods;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Il parser riceve testo uscito da uno pseudo-terminale, non una risposta HTTP
 * pulita: puo' avere rumore prima e dopo il JSON, sequenze ANSI, e ogni tanto
 * al posto dei dati c'e' un errore di kubectl. Sono questi i casi che contano.
 */
class PodListParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String LISTA_VALIDA = """
            {
              "apiVersion": "v1",
              "kind": "List",
              "items": [
                {"metadata": {"name": "zeta"}, "status": {"phase": "Running",
                  "containerStatuses": [{"ready": true, "restartCount": 0, "state": {"running": {}}}]}},
                {"metadata": {"name": "alfa"}, "status": {"phase": "Running",
                  "containerStatuses": [{"ready": true, "restartCount": 0, "state": {"running": {}}}]}}
              ]
            }
            """;

    private List<PodView> pods(String payload) {
        PodListParser.Result result = PodListParser.parse(payload, mapper);
        assertInstanceOf(PodListParser.Result.Pods.class, result,
                () -> "atteso successo, ottenuto: " + result);
        return ((PodListParser.Result.Pods) result).pods();
    }

    private String failure(String payload) {
        PodListParser.Result result = PodListParser.parse(payload, mapper);
        assertInstanceOf(PodListParser.Result.Failure.class, result,
                () -> "atteso fallimento, ottenuto: " + result);
        return ((PodListParser.Result.Failure) result).message();
    }

    @Test
    void listaValidaOrdinataPerNome() {
        List<PodView> pods = pods(LISTA_VALIDA);

        assertEquals(2, pods.size());
        assertEquals("alfa", pods.get(0).name());
        assertEquals("zeta", pods.get(1).name());
    }

    @Test
    void rumorePrimaDelJsonVieneIgnorato() {
        // L'eco del comando e il prompt precedono sempre l'output vero.
        String payload = "$ kubectl get pods -o json\n" + LISTA_VALIDA;

        assertEquals(2, pods(payload).size());
    }

    @Test
    void ilPromptDopoIlJsonNonRompeIlParsing() {
        // Caso piu' insidioso: dopo il JSON il terminale ristampa il prompt.
        String payload = LISTA_VALIDA + "\nmike@jump:~$ ";

        assertEquals(2, pods(payload).size());
    }

    @Test
    void sequenzeAnsiERitorniCarrelloVengonoRipuliti() {
        String payload = "[0m[32m\r\n" + LISTA_VALIDA.replace("\n", "\r\n") + "[0m";

        assertEquals(2, pods(payload).size());
    }

    @Test
    void namespaceVuotoEUnSuccessoNonUnErrore() {
        // kubectl non produce JSON quando non c'e' nulla: e' comunque uno stato sano.
        assertTrue(pods("No resources found in demo namespace.").isEmpty());
    }

    @Test
    void listaConItemsVuotoEUnSuccesso() {
        assertTrue(pods("{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[]}").isEmpty());
    }

    @Test
    void outputVuotoSpiegaCosaControllare() {
        String message = failure("   \n  ");

        assertTrue(message.contains("collegato"), () -> "messaggio poco utile: " + message);
    }

    @Test
    void erroreDiKubectlVieneRiportatoTaleEQuale() {
        String message = failure("error: You must be logged in to the server (Unauthorized)");

        assertEquals("error: You must be logged in to the server (Unauthorized)", message);
    }

    @Test
    void comandoNonTrovatoVieneRiportato() {
        String message = failure("bash: kubectl: command not found");

        assertEquals("bash: kubectl: command not found", message);
    }

    @Test
    void soloLaPrimaRigaDellErroreFinisceInUi() {
        String message = failure("""
                error: unable to connect
                dettagli lunghissimi che non servono
                altra riga
                """);

        assertEquals("error: unable to connect", message);
    }

    @Test
    void jsonMalformatoNonEsplodeMaSpiega() {
        String message = failure("{ questo non e' json valido ");

        assertTrue(message.contains("non interpretabile"), () -> "messaggio: " + message);
    }

    @Test
    void jsonValidoMaSenzaItemsEUnFallimento() {
        // Per esempio un singolo Pod invece di una List: meglio dirlo che
        // mostrare una tabella vuota facendo credere che non ci sia nulla.
        String message = failure("{\"kind\":\"Pod\",\"metadata\":{\"name\":\"solo\"}}");

        assertTrue(message.startsWith("{"), () -> "messaggio: " + message);
    }
}
