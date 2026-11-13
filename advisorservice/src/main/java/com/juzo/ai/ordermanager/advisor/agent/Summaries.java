package com.juzo.ai.ordermanager.advisor.agent;

import java.util.Map;
import java.util.Objects;

/** One-line, human-readable descriptions of tool calls for approval cards and activity chips. */
final class Summaries {

    private Summaries() {}

    static String describe(String tool, Map<String, Object> input) {
        return switch (tool) {
            case "place_order" -> {
                StringBuilder s = new StringBuilder()
                        .append(str(input.get("side"))).append(' ')
                        .append(str(input.get("quantity"))).append(' ')
                        .append(str(input.get("ticker"))).append(' ')
                        .append(str(input.get("type")));
                if (input.get("limitPrice") != null) s.append(" @ ").append(str(input.get("limitPrice")));
                yield s.append(" on account ").append(str(input.get("accountId"))).toString();
            }
            case "cancel_order" -> "Cancel order " + str(input.get("orderId"));
            case "get_market_prices" -> input.get("tickers") == null
                    ? "Fetched market prices"
                    : "Fetched prices for " + str(input.get("tickers"));
            default -> tool + " " + input;
        };
    }

    private static String str(Object value) {
        return Objects.toString(value, "?");
    }
}
