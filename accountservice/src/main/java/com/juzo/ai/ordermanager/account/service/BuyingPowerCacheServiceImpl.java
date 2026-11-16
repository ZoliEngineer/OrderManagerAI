package com.juzo.ai.ordermanager.account.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class BuyingPowerCacheServiceImpl implements BuyingPowerCacheService {

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final long ttlHours;
    private final Counter puts;
    private final Counter evictions;
    private final Counter hits;
    private final Counter misses;

    public BuyingPowerCacheServiceImpl(
            StringRedisTemplate redis,
            @Value("${app.cache.buying-power.key-prefix}") String keyPrefix,
            @Value("${app.cache.buying-power.ttl-hours}") long ttlHours,
            MeterRegistry meters) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttlHours = ttlHours;
        this.puts = op(meters, "put", "n/a");
        this.evictions = op(meters, "evict", "n/a");
        this.hits = op(meters, "get", "hit");
        this.misses = op(meters, "get", "miss");
    }

    private static Counter op(MeterRegistry meters, String operation, String result) {
        return Counter.builder("account.buying_power.cache")
                .description("Redis buying-power cache operations (read by the risk service)")
                .tag("operation", operation).tag("result", result)
                .register(meters);
    }

    @Override
    public void put(UUID accountId, BigDecimal buyingPower) {
        redis.opsForValue().set(key(accountId), buyingPower.toPlainString(), ttlHours, TimeUnit.HOURS);
        puts.increment();
    }

    @Override
    public Optional<BigDecimal> get(UUID accountId) {
        String value = redis.opsForValue().get(key(accountId));
        (value == null ? misses : hits).increment();
        return Optional.ofNullable(value).map(BigDecimal::new);
    }

    @Override
    public void evict(UUID accountId) {
        redis.delete(key(accountId));
        evictions.increment();
    }

    private String key(UUID accountId) {
        return keyPrefix + accountId;
    }
}
