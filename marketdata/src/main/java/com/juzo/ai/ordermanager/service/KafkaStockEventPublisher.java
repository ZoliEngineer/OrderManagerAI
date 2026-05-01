package com.juzo.ai.ordermanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaStockEventPublisher implements StockEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaStockEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String topic;

    public KafkaStockEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    @Value("${app.kafka.topic.marketdata}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(Stock stock) {
        try {
            String json = objectMapper.writeValueAsString(stock);
            kafkaTemplate.send(topic, stock.ticker(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Kafka publish failed for {}", stock.ticker(), ex);
                    } else {
                        log.debug("Price update published to Kafka topic {} [partition={}, offset={}]",
                            topic,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                    }
                });
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize stock {}", stock.ticker(), e);
        }
    }
}
