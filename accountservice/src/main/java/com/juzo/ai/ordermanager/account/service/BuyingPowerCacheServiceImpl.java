package com.juzo.ai.ordermanager.account.service;

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

    public BuyingPowerCacheServiceImpl(
            StringRedisTemplate redis,
            @Value("${app.cache.buying-power.key-prefix}") String keyPrefix,
            @Value("${app.cache.buying-power.ttl-hours}") long ttlHours) {
        this.redis = redis;
        this.keyPrefix = keyPrefix;
        this.ttlHours = ttlHours;
    }

    @Override
    public void put(UUID accountId, BigDecimal buyingPower) {
        redis.opsForValue().set(key(accountId), buyingPower.toPlainString(), ttlHours, TimeUnit.HOURS);
    }

    @Override
    public Optional<BigDecimal> get(UUID accountId) {
        String value = redis.opsForValue().get(key(accountId));
        return Optional.ofNullable(value).map(BigDecimal::new);
    }

    @Override
    public void evict(UUID accountId) {
        redis.delete(key(accountId));
    }

    private String key(UUID accountId) {
        return keyPrefix + accountId;
    }
}
