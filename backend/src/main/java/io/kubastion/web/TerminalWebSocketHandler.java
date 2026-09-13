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
 * Bridge between xterm.js in the browser and the PTY session.
 *
 * The client sends JSON messages (keystrokes, resizes); raw terminal text goes
 * back the other way and xterm writes it as-is.
 *
 * Closing the page does NOT kill the session: reload the browser and your login
 * is still there.
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
                default -> log.debug("ignored terminal message: {}", payload.path("type").asText());
            }
        } catch (Exception e) {
            log.debug("invalid terminal message: {}", e.toString());
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
            log.debug("terminal send failed: {}", e.toString());
        }
    }
}
