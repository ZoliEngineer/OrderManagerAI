package com.juzo.ai.ordermanager.execution.engine;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;

/** The Disruptor hops threads, so the trace context travels in the ring-buffer slot, not in thread-locals. */
class MatchingTraceHandOffTest {

    private final TestObservationRegistry registry = TestObservationRegistry.create();
    private final MatchingEngineDispatcher dispatcher =
            new MatchingEngineDispatcher(new SimpleMeterRegistry(), registry);

    @Test
    void matchSpanIsChildOfTheObservationStoredInTheSlot() {
        Observation listener = Observation.start("kafka.listener", registry);
        InboundEvent slot = newOrderSlot();
        slot.parentObservation = listener;
        listener.stop(); // the Kafka listener has returned by the time the engine thread runs

        dispatcher.onEvent(slot, 0, true);

        assertThat(registry)
                .hasObservationWithNameEqualTo("execution.match")
                .that()
                .hasParentObservationEqualTo(listener)
                .hasLowCardinalityKeyValue("ticker", "AAPL")
                .hasBeenStopped();
        org.junit.jupiter.api.Assertions.assertNull(slot.parentObservation, "slot must not keep the reference");
    }

    @Test
    void matchSpanStillRunsWithoutParent() {
        dispatcher.onEvent(newOrderSlot(), 0, true);
        assertThat(registry).hasObservationWithNameEqualTo("execution.match").that().hasBeenStopped();
    }

    private static InboundEvent newOrderSlot() {
        InboundEvent e = new InboundEvent();
        e.type = InboundEvent.Type.NEW_ORDER;
        e.ticker = "AAPL";
        e.orderId = UUID.randomUUID();
        e.side = Side.BUY;
        e.orderType = OrderType.LIMIT;
        e.quantity = new BigDecimal("10");
        e.limitPrice = new BigDecimal("100");
        e.timestampNanos = System.nanoTime();
        return e;
    }
}
