package com.juzo.ai.ordermanager.execution.engine;

import com.lmax.disruptor.EventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MatchingEngineDispatcher implements EventHandler<InboundEvent> {

    private final Map<String, SymbolMatchingEngine> engines = new HashMap<>();
    private final MeterRegistry meters;
    private final Timer queueWait;

    public MatchingEngineDispatcher(MeterRegistry meters) {
        this.meters = meters;
        this.queueWait = Timer.builder("execution.ringbuffer.wait")
                .description("Time an event waits in the ring buffer before the matching thread handles it")
                .publishPercentileHistogram()
                .register(meters);
    }

    @Override
    public void onEvent(InboundEvent event, long sequence, boolean endOfBatch) {
        queueWait.record(System.nanoTime() - event.timestampNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        engines.computeIfAbsent(event.ticker, t -> new SymbolMatchingEngine(t, meters))
               .onEvent(event);
    }
}
