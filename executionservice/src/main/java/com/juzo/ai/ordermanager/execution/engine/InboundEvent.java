package com.juzo.ai.ordermanager.execution.engine;

import java.math.BigDecimal;
import java.util.UUID;

public final class InboundEvent {

    public enum Type { NEW_ORDER, CANCEL_ORDER, MARKET_TICK }

    public Type type;
    public String ticker;
    public UUID orderId;
    public Side side;
    public OrderType orderType;
    public BigDecimal quantity;
    public BigDecimal limitPrice;
    public BigDecimal marketPrice;
    public long timestampNanos;

    public void clear() {
        type = null;
        ticker = null;
        orderId = null;
        side = null;
        orderType = null;
        quantity = null;
        limitPrice = null;
        marketPrice = null;
        timestampNanos = 0L;
    }
}
