package com.juzo.ai.ordermanager.order.dto;

import java.util.UUID;

public record OrderResponse(
    UUID orderId,
    String status,
    String message
) {}
