package com.juzo.ai.ordermanager.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MarketDataWebSocketHandler implements WebSocketHandler {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final String redisChannel;

    public MarketDataWebSocketHandler(
            ReactiveRedisMessageListenerContainer listenerContainer,
            @Value("${app.redis.channel.market-prices}") String redisChannel) {
        this.listenerContainer = listenerContainer;
        this.redisChannel = redisChannel;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Flux<WebSocketMessage> outbound = listenerContainer
                .receive(ChannelTopic.of(redisChannel))
                .map(message -> session.textMessage(message.getMessage()));

        // session.receive() must be consumed so proxy ping/control frames don't
        // cause back-pressure and trigger connection teardown in Azure's ARR proxy.
        return session.send(outbound).and(session.receive().then());
    }
}
