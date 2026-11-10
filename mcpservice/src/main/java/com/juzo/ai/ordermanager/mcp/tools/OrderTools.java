package com.juzo.ai.ordermanager.mcp.tools;

import com.juzo.ai.ordermanager.mcp.client.DownstreamException;
import com.juzo.ai.ordermanager.mcp.client.MarketDataClient;
import com.juzo.ai.ordermanager.mcp.client.OrderServiceClient;
import com.juzo.ai.ordermanager.mcp.dto.CancelOrderResult;
import com.juzo.ai.ordermanager.mcp.dto.OrderServiceResponse;
import com.juzo.ai.ordermanager.mcp.dto.OrderSide;
import com.juzo.ai.ordermanager.mcp.dto.OrderType;
import com.juzo.ai.ordermanager.mcp.dto.PlaceOrderCommand;
import com.juzo.ai.ordermanager.mcp.dto.PlaceOrderResult;
import com.juzo.ai.ordermanager.mcp.dto.StockPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);
    /** Structured audit trail of agent-initiated order actions (stand-in for the planned Audit Service). */
    private static final Logger audit = LoggerFactory.getLogger("audit.mcp");
    private static final Pattern TICKER = Pattern.compile("^[A-Z][A-Z0-9.\\-]{0,9}$");

    private final OrderServiceClient orderServiceClient;
    private final MarketDataClient marketDataClient;
    private final TradingGuardrails guardrails;

    public OrderTools(OrderServiceClient orderServiceClient, MarketDataClient marketDataClient,
                      TradingGuardrails guardrails) {
        this.orderServiceClient = orderServiceClient;
        this.marketDataClient = marketDataClient;
        this.guardrails = guardrails;
    }

    @McpTool(name = "place_order",
            title = "Place order",
            description = """
                    Places a stock order on behalf of the signed-in advisor for one of their trade accounts. \
                    Every order passes a pre-trade risk check (buying power) and per-order limits; a rejected \
                    order is returned with accepted=false and a reason code — it is NOT an error and must not \
                    be retried unchanged. Notes: LIMIT orders require limitPrice; MARKET orders must omit it. \
                    Quantities may be fractional. SELL orders are currently always rejected (positions are not \
                    tracked yet). Fills are not reported back yet. If the call fails with a timeout the order \
                    status is unknown — do not blindly retry, as that can create a duplicate order.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = true))
    public PlaceOrderResult placeOrder(
            McpSyncRequestContext context,
            @McpToolParam(description = "Trade account id (UUID) owned by the signed-in advisor") UUID accountId,
            @McpToolParam(description = "Ticker symbol, e.g. AAPL") String ticker,
            @McpToolParam(description = "BUY or SELL") OrderSide side,
            @McpToolParam(description = "MARKET or LIMIT") OrderType type,
            @McpToolParam(description = "Number of shares, greater than 0 (fractional allowed)") BigDecimal quantity,
            @McpToolParam(required = false, description = "Limit price per share; required for LIMIT, omit for MARKET")
            BigDecimal limitPrice) {
        CallerContext caller = CallerContext.from(context);
        String normalizedTicker = validate(accountId, ticker, side, type, quantity, limitPrice);
        PlaceOrderCommand command = new PlaceOrderCommand(accountId, normalizedTicker, side, type, quantity, limitPrice);

        Optional<String> guardrailRejection = guardrails.checkQuantity(quantity)
                .or(() -> guardrails.checkNotional(quantity, unitPriceFor(command, caller)));
        if (guardrailRejection.isPresent()) {
            PlaceOrderResult result = PlaceOrderResult.rejected(guardrailRejection.get());
            audit(caller, "place_order", command, result.status(), result.message());
            return result;
        }

        try {
            OrderServiceResponse response = orderServiceClient.placeOrder(command, caller.bearerToken());
            boolean accepted = !"REJECTED".equals(response.status());
            PlaceOrderResult result = new PlaceOrderResult(accepted, response.orderId(), response.status(), response.message());
            audit(caller, "place_order", command, result.status(), result.message());
            return result;
        } catch (DownstreamException e) {
            audit(caller, "place_order", command, "ERROR", e.getMessage());
            throw toToolError(e);
        }
    }

    @McpTool(name = "cancel_order",
            title = "Cancel order",
            description = """
                    Cancels an open order (status NEW, PENDING or PARTIALLY_FILLED) owned by the signed-in advisor. \
                    Returns cancelled=false with an explanation when the order does not exist or can no longer be \
                    cancelled (e.g. already FILLED, CANCELLED or REJECTED). Safe to call again for the same order.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = true))
    public CancelOrderResult cancelOrder(
            McpSyncRequestContext context,
            @McpToolParam(description = "Id (UUID) of the order to cancel, as returned by place_order") UUID orderId) {
        CallerContext caller = CallerContext.from(context);
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        try {
            orderServiceClient.cancelOrder(orderId, caller.bearerToken());
            CancelOrderResult result = new CancelOrderResult(orderId, true, "Order cancelled");
            audit(caller, "cancel_order", orderId, "CANCELLED", result.message());
            return result;
        } catch (DownstreamException e) {
            CancelOrderResult result = switch (e.statusCode()) {
                case 404 -> new CancelOrderResult(orderId, false, "Order not found for the signed-in advisor");
                case 409 -> new CancelOrderResult(orderId, false, e.getMessage());
                default -> null;
            };
            if (result == null) {
                audit(caller, "cancel_order", orderId, "ERROR", e.getMessage());
                throw toToolError(e);
            }
            audit(caller, "cancel_order", orderId, "NOT_CANCELLED", result.message());
            return result;
        }
    }

    private static String validate(UUID accountId, String ticker, OrderSide side, OrderType type,
                                   BigDecimal quantity, BigDecimal limitPrice) {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if (side == null) throw new IllegalArgumentException("side is required (BUY or SELL)");
        if (type == null) throw new IllegalArgumentException("type is required (MARKET or LIMIT)");
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker is required");
        String normalized = ticker.trim().toUpperCase(Locale.ROOT);
        if (!TICKER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ticker '" + ticker + "' is not a valid symbol");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be greater than 0");
        }
        if (type == OrderType.LIMIT && (limitPrice == null || limitPrice.signum() <= 0)) {
            throw new IllegalArgumentException("limitPrice greater than 0 is required for LIMIT orders");
        }
        if (type == OrderType.MARKET && limitPrice != null) {
            throw new IllegalArgumentException("limitPrice must be omitted for MARKET orders");
        }
        return normalized;
    }

    /** Price used for the notional guardrail: limit price, or current market price for market orders. */
    private BigDecimal unitPriceFor(PlaceOrderCommand command, CallerContext caller) {
        if (command.type() == OrderType.LIMIT) {
            return command.limitPrice();
        }
        if (!guardrails.notionalCheckEnabled()) {
            return null;
        }
        try {
            return marketDataClient.getPrices(caller.bearerToken()).stream()
                    .filter(p -> p.ticker().equals(command.ticker()))
                    .map(StockPrice::price)
                    .findFirst()
                    .orElse(null);
        } catch (DownstreamException e) {
            log.warn("Could not fetch market price for notional guardrail: {}", e.getMessage());
            return null;
        }
    }

    private static RuntimeException toToolError(DownstreamException e) {
        return switch (e.statusCode()) {
            case 401 -> new IllegalStateException("Not authorized by the Order Service — the access token was rejected");
            case 403 -> new IllegalStateException("Forbidden — the signed-in advisor may not perform this action");
            default -> new IllegalStateException(e.getMessage());
        };
    }

    private static void audit(CallerContext caller, String tool, Object args, String outcome, String message) {
        audit.info("tool={} subject={} clientId={} args={} outcome={} message={}",
                tool, caller.subject(), caller.clientId(), args, outcome, message);
    }
}
