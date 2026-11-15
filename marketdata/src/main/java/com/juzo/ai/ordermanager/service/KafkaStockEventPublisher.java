package com.juzo.ai.ordermanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.entity.Stock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaStockEventPublisher implements StockEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaStockEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final Counter ok;
    private final Counter failed;

    public KafkaStockEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    @Value("${app.kafka.topic.marketdata}") String topic,
                                    MeterRegistry meters) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.ok = publishCounter(meters, "success");
        this.failed = publishCounter(meters, "failure");
    }

    private static Counter publishCounter(MeterRegistry meters, String outcome) {
        return Counter.builder("marketdata.publish")
                .description("Price publishes to downstream channels by target and outcome")
                .tag("target", "kafka").tag("outcome", outcome)
                .register(meters);
    }

    @Override
    public void publish(Stock stock) {
        String key = stock.ticker();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(stock);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize stock {} for topic {}", key, topic, e);
            return;
        }
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        failed.increment();
                        log.error("Failed to publish market.prices for stock {}", key, ex);
                    } else {
                        ok.increment();
                        log.debug("Published market.prices partition={} offset={} ticker={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                key);
                    }
                });
    }
}
