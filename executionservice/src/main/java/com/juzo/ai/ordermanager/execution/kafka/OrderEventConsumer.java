package com.juzo.ai.ordermanager.execution.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.execution.dto.KafkaOrderEvent;
import com.juzo.ai.ordermanager.execution.engine.InboundEvent;
import com.juzo.ai.ordermanager.execution.engine.OrderType;
import com.juzo.ai.ordermanager.execution.engine.Side;
import com.lmax.disruptor.RingBuffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final RingBuffer<InboundEvent> ringBuffer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final Counter failures;
    private final Timer kafkaDelay;

    public OrderEventConsumer(RingBuffer<InboundEvent> ringBuffer, ObjectMapper objectMapper, MeterRegistry meters) {
        this.ringBuffer = ringBuffer;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.failures = Counter.builder("execution.events.failed")
                .description("Order events that could not be parsed or enqueued (currently swallowed and logged)")
                .register(meters);
        this.kafkaDelay = Timer.builder("execution.kafka.delay")
                .description("Producer timestamp to consumer receipt for order events")
                .publishPercentileHistogram()
                .register(meters);
        // Free slots in the 65,536-slot ring: a value near 0 means the matching thread cannot keep up.
        Gauge.builder("execution.ringbuffer.remaining", ringBuffer, RingBuffer::remainingCapacity)
                .description("Free slots in the Disruptor ring buffer")
                .register(meters);
    }

    @KafkaListener(
            topics = "${app.kafka.topic.order-created}",
            groupId = "${app.kafka.consumer-group.order-events}"
    )
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        long delayMs = System.currentTimeMillis() - record.timestamp();
        if (delayMs >= 0) {
            kafkaDelay.record(Duration.ofMillis(delayMs));
        }
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
            Counter.builder("execution.events.consumed")
                    .description("Order events enqueued onto the ring buffer, by event type")
                    .tag("type", event.eventType().toLowerCase())
                    .register(meters)
                    .increment();
        } catch (Exception e) {
            failures.increment();
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
