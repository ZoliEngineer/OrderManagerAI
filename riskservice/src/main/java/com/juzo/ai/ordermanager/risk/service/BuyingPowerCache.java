package com.juzo.ai.ordermanager.risk.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Counter hits;
    private final Counter misses;

    public BuyingPowerCache(
            StringRedisTemplate redis,
            @Value("${app.cache.buying-power.key-prefix}") String keyPrefix,
            MeterRegistry meters) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        // Resolve counters once: no registry lookup on the hot path.
        this.hits = Counter.builder("risk.buying_power.lookups").tag("result", "hit").register(meters);
        this.misses = Counter.builder("risk.buying_power.lookups").tag("result", "miss").register(meters);
    }

    public Optional<BigDecimal> get(UUID accountId) {
        String value = redis.opsForValue().get(keyPrefix + accountId);
        if (value == null) {
            misses.increment();
            log.warn("Buying power not found in cache for account={} (key expired or never written by the Account Service)", accountId);
            return Optional.empty();
        }
        hits.increment();
        log.debug("Buying power cache hit for account={}, value={}", accountId, value);
        return Optional.of(new BigDecimal(value));
    }
}
