package com.juzo.ai.ordermanager.risk.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class BuyingPowerCache {

    private static final Logger log = LoggerFactory.getLogger(BuyingPowerCache.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;

    public BuyingPowerCache(
            StringRedisTemplate redis,
            @Value("${app.cache.buying-power.key-prefix}") String keyPrefix) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
    }

    public Optional<BigDecimal> get(UUID accountId) {
        String value = redis.opsForValue().get(keyPrefix + accountId);
        if (value == null) {
            log.warn("Buying power not found in cache for account={}", accountId);
            return Optional.empty();
        }
        log.info("Buying power cache hit for account={}, value={}", accountId, value);
        return Optional.of(new BigDecimal(value));
    }
}
