package com.juzo.ai.ordermanager.mcp.dto;

import java.util.UUID;

/** Response body of the Order Service's {@code POST /api/orders} (both 201 and 422). */
public record OrderServiceResponse(UUID orderId, String status, String message) {}
