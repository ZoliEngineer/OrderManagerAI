package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisStockEventPublisherTest {

    private static final String CHANNEL = "market.prices";

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    private RedisStockEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RedisStockEventPublisher(redisTemplate, CHANNEL);
    }

    @Test
    void publishSendsToCorrectChannel() {
        Stock stock = new Stock("AAPL", "Apple Inc.", new BigDecimal("189.30"), BigDecimal.ZERO);
        when(redisTemplate.convertAndSend(eq(CHANNEL), any())).thenReturn(Mono.just(1L));

        publisher.publish(stock);

        verify(redisTemplate).convertAndSend(eq(CHANNEL), any(String.class));
    }

    @Test
    void publishSerializesStockAsJson() {
        Stock stock = new Stock("NVDA", "NVIDIA Corp.", new BigDecimal("875.40"), BigDecimal.ZERO);
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(redisTemplate.convertAndSend(eq(CHANNEL), payloadCaptor.capture())).thenReturn(Mono.just(1L));

        publisher.publish(stock);

        String json = payloadCaptor.getValue();
        assertThat(json).contains("\"ticker\":\"NVDA\"");
        assertThat(json).contains("\"price\":875.40");
    }

    @Test
    void publishDoesNotThrowOnRedisError() {
        Stock stock = new Stock("TSLA", "Tesla Inc.", new BigDecimal("177.80"), BigDecimal.ZERO);
        when(redisTemplate.convertAndSend(any(), any())).thenReturn(Mono.error(new RuntimeException("connection refused")));

        publisher.publish(stock);

        verify(redisTemplate).convertAndSend(eq(CHANNEL), any());
    }

    @Test
    void publishSerializesAllStockFields() {
        Stock stock = new Stock("MSFT", "Microsoft Corp.", new BigDecimal("415.50"), new BigDecimal("2.75"));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        when(redisTemplate.convertAndSend(eq(CHANNEL), payloadCaptor.capture())).thenReturn(Mono.just(1L));

        publisher.publish(stock);

        String json = payloadCaptor.getValue();
        assertThat(json).contains("\"ticker\":\"MSFT\"");
        assertThat(json).contains("\"name\":\"Microsoft Corp.\"");
        assertThat(json).contains("\"price\":415.50");
        assertThat(json).contains("\"change\":2.75");
    }
}
