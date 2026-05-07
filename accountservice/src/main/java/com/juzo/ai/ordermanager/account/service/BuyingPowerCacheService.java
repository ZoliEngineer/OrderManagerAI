package com.juzo.ai.ordermanager.account.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface BuyingPowerCacheService {

    void put(UUID accountId, BigDecimal buyingPower);

    Optional<BigDecimal> get(UUID accountId);

    void evict(UUID accountId);
}
