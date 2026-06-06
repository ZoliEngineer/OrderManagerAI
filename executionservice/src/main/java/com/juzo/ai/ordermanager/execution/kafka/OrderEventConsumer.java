package com.juzo.ai.ordermanager.execution.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.execution.dto.KafkaOrderEvent;
import com.juzo.ai.ordermanager.execution.engine.InboundEvent;
import com.juzo.ai.ordermanager.execution.engine.OrderType;
import com.juzo.ai.ordermanager.execution.engine.Side;
import com.lmax.disruptor.RingBuffer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final RingBuffer<InboundEvent> ringBuffer;
    private final ObjectMapper objectMapper;

    public OrderEventConsumer(RingBuffer<InboundEvent> ringBuffer, ObjectMapper objectMapper) {
        this.ringBuffer = ringBuffer;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${app.kafka.topic.order-created}",
            groupId = "${app.kafka.consumer-group.order-events}"
    )
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        try {
            KafkaOrderEvent event = objectMapper.readValue(record.value(), KafkaOrderEvent.class);
            KafkaOrderEvent.KafkaOrder order = event.order();
            if (order == null) {
                log.warn("Received order event with null order body key={}", record.key());
                return;
            }

            long seq = ringBuffer.next();
            try {
                InboundEvent slot = ringBuffer.get(seq);
                slot.clear();
                slot.type = toInboundType(event.eventType());
                slot.ticker = order.ticker();
                slot.orderId = UUID.fromString(order.id());
                slot.side = Side.valueOf(order.side());
                slot.orderType = OrderType.valueOf(order.type());
                slot.quantity = order.quantity();
                slot.limitPrice = order.limitPrice();
                slot.timestampNanos = System.nanoTime();
            } finally {
                ringBuffer.publish(seq);
            }
        } catch (Exception e) {
            log.error("Failed to process order event key={}", record.key(), e);
        }
    }

    private InboundEvent.Type toInboundType(String eventType) {
        return switch (eventType) {
            case "ORDER_CREATED"   -> InboundEvent.Type.NEW_ORDER;
            case "ORDER_CANCELLED" -> InboundEvent.Type.CANCEL_ORDER;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
