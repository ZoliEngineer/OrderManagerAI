package com.juzo.ai.ordermanager.mcp.tools;

import com.juzo.ai.ordermanager.mcp.client.MarketDataClient;
import com.juzo.ai.ordermanager.mcp.client.OrderServiceClient;
import com.juzo.ai.ordermanager.mcp.config.McpTransportConfig;
import com.juzo.ai.ordermanager.mcp.config.TradingGuardrailProperties;
import com.juzo.ai.ordermanager.mcp.dto.CancelOrderResult;
import com.juzo.ai.ordermanager.mcp.dto.OrderSide;
import com.juzo.ai.ordermanager.mcp.dto.OrderType;
import com.juzo.ai.ordermanager.mcp.dto.PlaceOrderResult;
import com.juzo.ai.ordermanager.mcp.dto.StockPrice;
import io.modelcontextprotocol.common.McpTransportContext;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderToolsTest {

    private static final String TOKEN = "test-token";
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private MockWebServer orderServer;
    private MockWebServer marketServer;
    private McpSyncRequestContext context;

    @BeforeEach
    void setUp() throws Exception {
        orderServer = new MockWebServer();
        marketServer = new MockWebServer();
        orderServer.start();
        marketServer.start();
        context = mock(McpSyncRequestContext.class);
        when(context.transportContext()).thenReturn(McpTransportContext.create(Map.of(
                McpTransportConfig.CTX_BEARER_TOKEN, TOKEN,
                McpTransportConfig.CTX_SUBJECT, "advisor-sub",
                McpTransportConfig.CTX_CLIENT_ID, "agent-client")));
    }

    @AfterEach
    void tearDown() throws Exception {
        orderServer.shutdown();
        marketServer.shutdown();
    }

    private OrderTools tools(BigDecimal maxQty, BigDecimal maxNotional) {
        OrderServiceClient orders = new OrderServiceClient(RestClient.create(orderServer.url("/").toString()));
        MarketDataClient market = new MarketDataClient(RestClient.create(marketServer.url("/").toString()));
        return new OrderTools(orders, market, new TradingGuardrails(new TradingGuardrailProperties(maxQty, maxNotional)));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void placeOrder_accepted_forwardsTokenAndBody() throws Exception {
        orderServer.enqueue(json(201, "{\"orderId\":\"" + ORDER + "\",\"status\":\"PENDING\",\"message\":\"Order accepted\"}"));

        PlaceOrderResult result = tools(null, null).placeOrder(context, ACCOUNT, " aapl ", OrderSide.BUY,
                OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("150.25"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.orderId()).isEqualTo(ORDER);
        assertThat(result.status()).isEqualTo("PENDING");

        RecordedRequest request = orderServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/api/orders");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"ticker\":\"AAPL\"", "\"side\":\"BUY\"", "\"type\":\"LIMIT\"",
                "\"accountId\":\"" + ACCOUNT + "\"", "\"limitPrice\":150.25");
    }

    @Test
    void placeOrder_riskRejection_isReturnedAsResultNotError() {
        orderServer.enqueue(json(422, "{\"orderId\":null,\"status\":\"REJECTED\",\"message\":\"INSUFFICIENT_FUNDS — not enough buying power\"}"));

        PlaceOrderResult result = tools(null, null).placeOrder(context, ACCOUNT, "AAPL", OrderSide.BUY,
                OrderType.LIMIT, BigDecimal.ONE, BigDecimal.TEN);

        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.message()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    void placeOrder_validationErrors_doNotCallOrderService() {
        OrderTools tools = tools(null, null);

        assertThatThrownBy(() -> tools.placeOrder(context, ACCOUNT, "AAPL", OrderSide.BUY, OrderType.LIMIT, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limitPrice");
        assertThatThrownBy(() -> tools.placeOrder(context, ACCOUNT, "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("omitted");
        assertThatThrownBy(() -> tools.placeOrder(context, ACCOUNT, "AAPL", OrderSide.BUY, OrderType.MARKET, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
        assertThatThrownBy(() -> tools.placeOrder(context, ACCOUNT, "AA PL;", OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ticker");

        assertThat(orderServer.getRequestCount()).isZero();
    }

    @Test
    void placeOrder_overQuantityLimit_rejectedWithoutCallingOrderService() {
        PlaceOrderResult result = tools(new BigDecimal("100"), null).placeOrder(context, ACCOUNT, "AAPL",
                OrderSide.BUY, OrderType.LIMIT, new BigDecimal("101"), BigDecimal.ONE);

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).startsWith("GUARDRAIL_MAX_QUANTITY");
        assertThat(orderServer.getRequestCount()).isZero();
    }

    @Test
    void placeOrder_marketOrderOverNotionalLimit_usesMarketPrice() throws Exception {
        marketServer.enqueue(json(200, "[{\"ticker\":\"AAPL\",\"name\":\"Apple\",\"price\":200.00}]"));

        PlaceOrderResult result = tools(null, new BigDecimal("1000")).placeOrder(context, ACCOUNT, "AAPL",
                OrderSide.BUY, OrderType.MARKET, new BigDecimal("6"), null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).startsWith("GUARDRAIL_MAX_NOTIONAL");
        assertThat(marketServer.takeRequest(1, TimeUnit.SECONDS).getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(orderServer.getRequestCount()).isZero();
    }

    @Test
    void placeOrder_marketOrderWithUnknownPrice_rejectedWhenNotionalLimitEnabled() {
        marketServer.enqueue(json(200, "[]"));

        PlaceOrderResult result = tools(null, new BigDecimal("1000")).placeOrder(context, ACCOUNT, "AAPL",
                OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE, null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).startsWith("PRICE_UNAVAILABLE");
    }

    @Test
    void placeOrder_unauthorized_isToolError() {
        orderServer.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> tools(null, null).placeOrder(context, ACCOUNT, "AAPL", OrderSide.BUY,
                OrderType.LIMIT, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Not authorized");
    }

    @Test
    void placeOrder_withoutCallerToken_isRejected() {
        when(context.transportContext()).thenReturn(McpTransportContext.create(Map.of()));

        assertThatThrownBy(() -> tools(null, null).placeOrder(context, ACCOUNT, "AAPL", OrderSide.BUY,
                OrderType.LIMIT, BigDecimal.ONE, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("credentials");
        assertThat(orderServer.getRequestCount()).isZero();
    }

    @Test
    void cancelOrder_success() throws Exception {
        orderServer.enqueue(new MockResponse().setResponseCode(204));

        CancelOrderResult result = tools(null, null).cancelOrder(context, ORDER);

        assertThat(result.cancelled()).isTrue();
        RecordedRequest request = orderServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request.getMethod()).isEqualTo("DELETE");
        assertThat(request.getPath()).isEqualTo("/api/orders/" + ORDER);
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void cancelOrder_notCancellableAndNotFound_areResultsNotErrors() {
        orderServer.enqueue(json(409, "{\"status\":409,\"message\":\"Order cannot be cancelled in status: FILLED\"}"));
        orderServer.enqueue(json(404, "{\"status\":404}"));
        OrderTools tools = tools(null, null);

        CancelOrderResult conflict = tools.cancelOrder(context, ORDER);
        CancelOrderResult missing = tools.cancelOrder(context, ORDER);

        assertThat(conflict.cancelled()).isFalse();
        assertThat(conflict.message()).contains("409");
        assertThat(missing.cancelled()).isFalse();
        assertThat(missing.message()).contains("not found");
    }

    @Test
    void cancelOrder_serverError_isToolError() {
        orderServer.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> tools(null, null).cancelOrder(context, ORDER))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("500");
    }

    @Test
    void getMarketPrices_filtersByTicker() {
        marketServer.enqueue(json(200, "[{\"ticker\":\"AAPL\",\"price\":200},{\"ticker\":\"MSFT\",\"price\":400}]"));
        MarketDataTools tools = new MarketDataTools(new MarketDataClient(RestClient.create(marketServer.url("/").toString())));

        List<StockPrice> prices = tools.getMarketPrices(context, List.of("msft"));

        assertThat(prices).extracting(StockPrice::ticker).containsExactly("MSFT");
    }

    @Test
    void getMarketPrices_noContent_returnsEmptyList() {
        marketServer.enqueue(new MockResponse().setResponseCode(204));
        MarketDataTools tools = new MarketDataTools(new MarketDataClient(RestClient.create(marketServer.url("/").toString())));

        assertThat(tools.getMarketPrices(context, null)).isEmpty();
    }
}
