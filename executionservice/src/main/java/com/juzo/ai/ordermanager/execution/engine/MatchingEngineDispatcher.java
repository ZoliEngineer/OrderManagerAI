package com.juzo.ai.ordermanager.execution.engine;

import com.lmax.disruptor.EventHandler;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class MatchingEngineDispatcher implements EventHandler<InboundEvent> {

    private final Map<String, SymbolMatchingEngine> engines = new HashMap<>();

    @Override
    public void onEvent(InboundEvent event, long sequence, boolean endOfBatch) {
        engines.computeIfAbsent(event.ticker, SymbolMatchingEngine::new)
               .onEvent(event);
    }
}
