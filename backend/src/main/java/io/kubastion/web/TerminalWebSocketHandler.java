package io.kubastion.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.terminal.TerminalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Ponte fra xterm.js nel browser e la sessione PTY.
 *
 * Dal client arrivano messaggi JSON (tasti premuti, ridimensionamenti); verso
 * il client va il testo grezzo del terminale, che xterm scrive cosi' com'e'.
 *
 * Alla chiusura della pagina la sessione NON viene terminata: se ricarichi il
 * browser ritrovi il tuo login ancora aperto.
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    private final TerminalService terminal;
    private final ObjectMapper mapper;
    private final Map<String, Consumer<String>> listeners = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(TerminalService terminal, ObjectMapper mapper) {
        this.terminal = terminal;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            terminal.ensureStarted();
        } catch (RuntimeException e) {
            send(session, "\r\n[kubastion] " + e.getMessage() + "\r\n");
            return;
        }
        Consumer<String> listener = text -> send(session, text);
        listeners.put(session.getId(), listener);
        terminal.addListener(listener);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode payload = mapper.readTree(message.getPayload());
            switch (payload.path("type").asText()) {
                case "input" -> terminal.write(payload.path("data").asText(""));
                case "resize" -> terminal.resize(
                        payload.path("cols").asInt(0), payload.path("rows").asInt(0));
                default -> log.debug("messaggio terminale ignorato: {}", payload.path("type").asText());
            }
        } catch (Exception e) {
            log.debug("messaggio terminale non valido: {}", e.toString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Consumer<String> listener = listeners.remove(session.getId());
        if (listener != null) {
            terminal.removeListener(listener);
        }
    }

    private void send(WebSocketSession session, String text) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception e) {
            log.debug("invio al terminale fallito: {}", e.toString());
        }
    }
}
