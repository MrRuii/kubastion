package io.kubastion.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PodsWebSocketHandler handler;

    public WebSocketConfig(PodsWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Solo origini locali: kubastion gira sulla tua macchina e con nessun altro parla.
        registry.addHandler(handler, "/ws/pods")
                .setAllowedOrigins("http://localhost:4200", "http://127.0.0.1:4200",
                        "http://localhost:8080", "http://127.0.0.1:8080");
    }
}
