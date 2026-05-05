package com.juzo.ai.ordermanager.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountDetails(UUID id, String displayName, BigDecimal cashBalance) {}
