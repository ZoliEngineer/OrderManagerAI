package com.juzo.ai.ordermanager.mcp.tools;

import com.juzo.ai.ordermanager.mcp.config.TradingGuardrailProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** Per-order limits for agent-initiated orders. Returns a rejection message, or empty when the order passes. */
@Component
public class TradingGuardrails {

    private final TradingGuardrailProperties props;

    public TradingGuardrails(TradingGuardrailProperties props) {
        this.props = props;
    }

    public boolean notionalCheckEnabled() {
        return props.maxNotionalPerOrder() != null;
    }

    public Optional<String> checkQuantity(BigDecimal quantity) {
        BigDecimal max = props.maxQuantityPerOrder();
        if (max != null && quantity.compareTo(max) > 0) {
            return Optional.of("GUARDRAIL_MAX_QUANTITY — quantity " + quantity.toPlainString()
                    + " exceeds the per-order limit of " + max.toPlainString());
        }
        return Optional.empty();
    }

    /** @param unitPrice limit price, or current market price for market orders; null if unknown */
    public Optional<String> checkNotional(BigDecimal quantity, BigDecimal unitPrice) {
        BigDecimal max = props.maxNotionalPerOrder();
        if (max == null) {
            return Optional.empty();
        }
        if (unitPrice == null) {
            return Optional.of("PRICE_UNAVAILABLE — cannot verify the order value against the per-order limit");
        }
        BigDecimal notional = quantity.multiply(unitPrice);
        if (notional.compareTo(max) > 0) {
            return Optional.of("GUARDRAIL_MAX_NOTIONAL — order value " + notional.stripTrailingZeros().toPlainString()
                    + " exceeds the per-order limit of " + max.toPlainString());
        }
        return Optional.empty();
    }
}
