package com.juzo.ai.ordermanager.execution.config;

import com.juzo.ai.ordermanager.execution.engine.InboundEvent;
import com.juzo.ai.ordermanager.execution.engine.MatchingEngineDispatcher;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DisruptorConfig {

    @Bean(destroyMethod = "shutdown")
    public Disruptor<InboundEvent> disruptor(MatchingEngineDispatcher dispatcher) {
        Disruptor<InboundEvent> disruptor = new Disruptor<>(
                InboundEvent::new,
                65536,
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                new YieldingWaitStrategy()
        );
        disruptor.handleEventsWith(dispatcher);
        disruptor.start();
        return disruptor;
    }

    @Bean
    public RingBuffer<InboundEvent> ringBuffer(Disruptor<InboundEvent> disruptor) {
        return disruptor.getRingBuffer();
    }
}
