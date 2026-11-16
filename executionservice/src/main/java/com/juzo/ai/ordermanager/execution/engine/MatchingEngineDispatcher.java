package com.juzo.ai.ordermanager.execution.engine;

import com.lmax.disruptor.EventHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MatchingEngineDispatcher implements EventHandler<InboundEvent> {

    private final Map<String, SymbolMatchingEngine> engines = new HashMap<>();
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final Timer queueWait;

    public MatchingEngineDispatcher(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
        this.queueWait = Timer.builder("execution.ringbuffer.wait")
                .description("Time an event waits in the ring buffer before the matching thread handles it")
                .publishPercentileHistogram()
                .register(meters);
    }

    @Override
    public void onEvent(InboundEvent event, long sequence, boolean endOfBatch) {
        queueWait.record(System.nanoTime() - event.timestampNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        // The ring buffer hops threads, so the trace context is restored by hand from the slot.
        Observation match = Observation.createNotStarted("execution.match", observations)
                .parentObservation(event.parentObservation)
                .lowCardinalityKeyValue("ticker", String.valueOf(event.ticker))
                .lowCardinalityKeyValue("event.type", String.valueOf(event.type))
                .start();
        try (Observation.Scope ignored = match.openScope()) {
            engines.computeIfAbsent(event.ticker, t -> new SymbolMatchingEngine(t, meters))
                   .onEvent(event);
        } catch (RuntimeException e) {
            match.error(e);
            throw e;
        } finally {
            match.stop();
            event.parentObservation = null;
        }
    }
}
