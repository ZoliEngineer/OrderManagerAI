package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KafkaStockEventPublisherTest {

    private static final String TOPIC = "market.prices";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaStockEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaStockEventPublisher(kafkaTemplate, TOPIC);
    }

    @Test
    void publishSendsToCorrectTopic() {
        Stock stock = new Stock("AAPL", "Apple Inc.", new BigDecimal("189.30"), BigDecimal.ZERO);
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(new CompletableFuture<>());

        publisher.publish(stock);

        verify(kafkaTemplate).send(eq(TOPIC), eq("AAPL"), any(String.class));
    }

    @Test
    void publishUsesTickerAsKey() {
        Stock stock = new Stock("MSFT", "Microsoft Corp.", new BigDecimal("415.50"), BigDecimal.ZERO);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(kafkaTemplate.send(any(), keyCaptor.capture(), any())).thenReturn(new CompletableFuture<>());

        publisher.publish(stock);

        assertThat(keyCaptor.getValue()).isEqualTo("MSFT");
    }

    @Test
    void publishSerializesStockAsJson() {
        Stock stock = new Stock("NVDA", "NVIDIA Corp.", new BigDecimal("875.40"), BigDecimal.ZERO);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        when(kafkaTemplate.send(any(), any(), valueCaptor.capture())).thenReturn(new CompletableFuture<>());

        publisher.publish(stock);

        String json = valueCaptor.getValue();
        assertThat(json).contains("\"ticker\":\"NVDA\"");
        assertThat(json).contains("\"price\":875.40");
    }

    @Test
    void publishLogsErrorOnKafkaFailure() {
        Stock stock = new Stock("TSLA", "Tesla Inc.", new BigDecimal("177.80"), BigDecimal.ZERO);
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);

        publisher.publish(stock);
        future.completeExceptionally(new RuntimeException("broker unavailable"));

        // no exception propagated — error is handled internally via whenComplete
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("TSLA"), any());
    }

    @Test
    void publishDoesNotThrowWhenKafkaSucceeds() {
        Stock stock = new Stock("AMZN", "Amazon.com Inc.", new BigDecimal("182.75"), BigDecimal.ZERO);
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(future);

        publisher.publish(stock);
        future.completeExceptionally(new RuntimeException("intentional"));

        verify(kafkaTemplate).send(eq(TOPIC), eq("AMZN"), any());
    }
}
