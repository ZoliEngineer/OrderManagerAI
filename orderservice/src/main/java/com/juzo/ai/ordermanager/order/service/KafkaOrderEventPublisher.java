package com.juzo.ai.ordermanager.order.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.order.entity.Order;

@Service
public class KafkaOrderEventPublisher implements OrderEventPublisher {

    private record OrderEvent(String eventType, Order order) {}

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String orderEventsTopic;

    public KafkaOrderEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper,
                                    @Value("${app.kafka.topic.order.events}") String orderEventsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.orderEventsTopic = orderEventsTopic;
    }

    @Override
    public void publishOrderCreated(Order order) {
        publish("ORDER_CREATED", order);
    }

    @Override
    public void publishOrderCancelled(Order order) {
        publish("ORDER_CANCELLED", order);
    }

    private void publish(String eventType, Order order) {
        String key = order.id().toString();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new OrderEvent(eventType, order));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order {} eventType {}", key, eventType, e);
            return;
        }
        kafkaTemplate.send(orderEventsTopic, key, payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} for order {}", eventType, key, ex);
                    } else {
                        log.debug("Published {} partition={} offset={} orderId={}",
                                eventType,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                key);
                    }
                });
    }
}
