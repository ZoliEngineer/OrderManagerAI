package com.juzo.ai.ordermanager.mcp.dto;

import java.util.UUID;

public record CancelOrderResult(UUID orderId, boolean cancelled, String message) {}
