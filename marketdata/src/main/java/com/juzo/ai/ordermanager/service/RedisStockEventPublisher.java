package com.juzo.ai.ordermanager.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.entity.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RedisStockEventPublisher implements StockEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RedisStockEventPublisher.class);

    private final AtomicBoolean firstPublishDone = new AtomicBoolean(false);
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String redisChannel;

    public RedisStockEventPublisher(ReactiveRedisTemplate<String, String> redisTemplate,
                                    @Value("${app.redis.channel.market-prices}") String redisChannel) {
        this.redisTemplate = redisTemplate;
        this.redisChannel = redisChannel;
    }

    @Override
    public void publish(Stock stock) {
        try {
            String json = objectMapper.writeValueAsString(stock);
            redisTemplate.convertAndSend(redisChannel, json).subscribe(
                this::logPublishSuccess,
                err -> log.error("Redis publish failed for {}", stock.ticker(), err)
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize stock {}", stock.ticker(), e);
        }
    }

    private void logPublishSuccess(Long subscriberCount) {
        if (firstPublishDone.compareAndSet(false, true)) {
            log.info("Redis connection verified: first price update published successfully ({} subscribers)", subscriberCount);
        } else {
            log.debug("Price update published to Redis ({} subscribers)", subscriberCount);
        }
    }
}
