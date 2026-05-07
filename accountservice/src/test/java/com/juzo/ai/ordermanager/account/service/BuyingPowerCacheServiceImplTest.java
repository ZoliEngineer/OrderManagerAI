package com.juzo.ai.ordermanager.account.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BuyingPowerCacheServiceImplTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private BuyingPowerCacheServiceImpl cacheService;

    private final UUID accountId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final String expectedKey = "account:buying-power:11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        cacheService = new BuyingPowerCacheServiceImpl(redis, "account:buying-power:", 24);
    }

    @Test
    void put_storesValueWithCorrectKeyAndTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);
        BigDecimal buyingPower = new BigDecimal("12345.6789");

        cacheService.put(accountId, buyingPower);

        verify(valueOps).set(expectedKey, "12345.6789", 24L, TimeUnit.HOURS);
    }

    @Test
    void get_returnsValueWhenKeyExists() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(expectedKey)).thenReturn("99000.0000");

        Optional<BigDecimal> result = cacheService.get(accountId);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualByComparingTo(new BigDecimal("99000.0000"));
    }

    @Test
    void get_returnsEmptyWhenKeyMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(expectedKey)).thenReturn(null);

        Optional<BigDecimal> result = cacheService.get(accountId);

        assertThat(result).isEmpty();
    }

    @Test
    void evict_deletesCorrectKey() {
        cacheService.evict(accountId);

        verify(redis).delete(expectedKey);
    }
}
