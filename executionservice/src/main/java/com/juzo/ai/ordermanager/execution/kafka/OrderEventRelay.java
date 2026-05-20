package com.juzo.ai.ordermanager.execution.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderEventRelay {

    private static final Logger log = LoggerFactory.getLogger(OrderEventRelay.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String executionReportTopic;

    public OrderEventRelay(KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper,
                           @Value("${app.kafka.topic.execution-report}") String executionReportTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.executionReportTopic = executionReportTopic;
    }

    @KafkaListener(topics = "${app.kafka.topic.order-created}", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        String orderId = extractOrderId(record);
        String summary = summarize(record.value());

        log.info("Received order event key={} summary={}", record.key(), summary);

        kafkaTemplate.send(executionReportTopic, orderId, summary)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to relay execution report for orderId={}", orderId, ex);
                    } else {
                        log.debug("Relayed execution report orderId={} partition={} offset={}",
                                orderId,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    private String extractOrderId(ConsumerRecord<String, String> record) {
        try {
            JsonNode root = objectMapper.readTree(record.value());
            JsonNode id = root.path("order").path("id");
            return id.isMissingNode() ? record.key() : id.asText();
        } catch (Exception e) {
            return record.key();
        }
    }

    private String summarize(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode order = root.path("order");
            return String.format("eventType=%s orderId=%s ticker=%s side=%s type=%s qty=%s limitPrice=%s status=%s",
                    root.path("eventType").asText(),
                    order.path("id").asText(),
                    order.path("ticker").asText(),
                    order.path("side").asText(),
                    order.path("type").asText(),
                    order.path("quantity").asText(),
                    order.path("limitPrice").asText("null"),
                    order.path("status").asText());
        } catch (Exception e) {
            log.warn("Could not parse order event JSON, forwarding raw", e);
            return rawJson;
        }
    }
}
