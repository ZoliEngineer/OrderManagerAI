package com.juzo.ai.ordermanager.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

@Component
public class MarketDataWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketDataWebSocketHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final String redisChannel;
    private final ReactiveJwtDecoder jwtDecoder;

    public MarketDataWebSocketHandler(
            ReactiveRedisMessageListenerContainer listenerContainer,
            @Value("${app.redis.channel.market-prices}") String redisChannel,
            ReactiveJwtDecoder jwtDecoder) {
        this.listenerContainer = listenerContainer;
        this.redisChannel = redisChannel;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket connection established: sessionId={}", session.getId());

        // Sink bridges inbound auth validation to the outbound stream.
        // The outbound flux delays subscription until the sink emits a validated JWT.
        Sinks.One<Jwt> authSink = Sinks.one();

        // Inbound: first message must be the auth frame; subsequent messages are drained
        // so proxy ping/control frames don't cause back-pressure in Azure's ARR proxy.
        Flux<Void> inboundProcessing = session.receive()
                .index()
                .flatMap(tuple -> {
                    if (tuple.getT1() == 0) {
                        return parseAuthFrame(tuple.getT2())
                                .doOnNext(jwt -> {
                                    log.info("WebSocket authenticated: sessionId={}, sub={}", session.getId(), jwt.getSubject());
                                    authSink.tryEmitValue(jwt);
                                })
                                .doOnError(e -> authSink.tryEmitError(e))
                                .then();
                    }
                    return Mono.empty();
                });

        // Outbound: starts only after authSink emits a valid JWT
        Flux<WebSocketMessage> outbound = authSink.asMono()
                .flatMapMany(jwt -> listenerContainer
                        .receive(ChannelTopic.of(redisChannel))
                        .doOnNext(m -> log.debug("Price update: sessionId={}, payload={}", session.getId(), m.getMessage()))
                        .map(m -> session.textMessage(m.getMessage()))
                        .doOnError(e -> log.error("Redis stream error: sessionId={}", session.getId(), e)));

        return session.send(outbound)
                .and(inboundProcessing)
                .doOnError(e -> log.error("WebSocket session error: sessionId={}", session.getId(), e))
                .doFinally(signal -> log.info("WebSocket connection closed: sessionId={}, signal={}", session.getId(), signal))
                .onErrorResume(e -> {
                    log.warn("WebSocket auth failed: sessionId={}", session.getId(), e);
                    return session.close(CloseStatus.POLICY_VIOLATION);
                });
    }

    private Mono<Jwt> parseAuthFrame(WebSocketMessage msg) {
        try {
            JsonNode json = MAPPER.readTree(msg.getPayloadAsText());
            if (!"auth".equals(json.path("type").asText(null))) {
                return Mono.error(new SecurityException("Expected auth frame as first message"));
            }
            return jwtDecoder.decode(json.path("token").asText(""));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
