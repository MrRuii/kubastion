package io.kubastion.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubastion.pods.PodMonitorService;
import io.kubastion.pods.PodsSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Spinge lo stato dei pod alla UI appena cambia. Alla connessione manda subito
 * lo snapshot corrente, cosi' la tabella e' popolata senza aspettare il primo
 * evento del cluster.
 */
@Component
public class PodsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PodsWebSocketHandler.class);

    private final PodMonitorService monitor;
    private final ObjectMapper mapper;
    private final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();
    private final Consumer<PodsSnapshot> listener = this::broadcast;

    public PodsWebSocketHandler(PodMonitorService monitor, ObjectMapper mapper) {
        this.monitor = monitor;
        this.mapper = mapper;
    }

    @PostConstruct
    void register() {
        monitor.addListener(listener);
    }

    @PreDestroy
    void unregister() {
        monitor.removeListener(listener);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        send(session, monitor.snapshot());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    private void broadcast(PodsSnapshot snapshot) {
        for (WebSocketSession session : sessions) {
            send(session, snapshot);
        }
    }

    private void send(WebSocketSession session, PodsSnapshot snapshot) {
        try {
            String payload = mapper.writeValueAsString(snapshot);
            // WebSocketSession non e' thread-safe: gli invii vanno serializzati
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            log.debug("invio websocket fallito, sessione rimossa: {}", e.toString());
            sessions.remove(session);
        }
    }
}
