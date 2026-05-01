package com.juzo.ai.ordermanager.service;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.juzo.ai.ordermanager.entity.Stock;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class FinnhubPriceSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API_KEY = "test-api-key";
    private static final String WS_URL  = "wss://localhost:0"; // never connected in unit tests

    private MockWebServer mockServer;
    private FinnhubPriceSource source;
    private List<Stock> updates;

    @BeforeEach
    void setUp() throws IOException, ReflectiveOperationException {
        mockServer = new MockWebServer();
        mockServer.start();

        // Explicit codec registration is required outside a Spring Boot application context,
        // where WebClient.Builder auto-configuration does not run.
        WebClient webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString())
            .codecs(cfg -> {
                cfg.defaultCodecs().jackson2JsonDecoder(new Jackson2JsonDecoder());
                cfg.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder());
            })
            .build();

        source = new FinnhubPriceSource(API_KEY, WS_URL, webClient, new ReactorNettyWebSocketClient());

        updates = new ArrayList<>();
        injectOnUpdate(source, updates::add);
    }

    @AfterEach
    void tearDown() throws IOException {
        source.stop();
        mockServer.shutdown();
    }

    // ── initialStocks ────────────────────────────────────────────────────────

    @Test
    void initialStocks_returnsTenDefaultSymbols() {
        assertThat(source.initialStocks()).hasSize(10);
    }

    @Test
    void initialStocks_containsExpectedTickers() {
        List<String> tickers = source.initialStocks().stream().map(Stock::ticker).toList();
        assertThat(tickers).containsExactlyInAnyOrder(
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "BRK.B", "LLY", "JPM", "TSLA"
        );
    }

    @Test
    void initialStocks_allStartAtZeroPrice() {
        source.initialStocks().forEach(s ->
            assertThat(s.price()).isEqualByComparingTo(BigDecimal.ZERO)
        );
    }

    // ── applyQuote (REST quote parsing logic) ────────────────────────────────

    @Test
    void applyQuote_parsesNonZeroPriceAndCallsOnUpdate() {
        source.applyQuote("AAPL", quoteNode(182.50, 1.25));

        assertThat(updates).hasSize(1);
        Stock result = updates.get(0);
        assertThat(result.ticker()).isEqualTo("AAPL");
        assertThat(result.price()).isEqualByComparingTo("182.50");
        assertThat(result.totalChange()).isEqualByComparingTo("1.25");
    }

    @Test
    void applyQuote_zeroPriceSkipsOnUpdate() {
        source.applyQuote("AAPL", quoteNode(0, 0));

        assertThat(updates).isEmpty();
    }

    @Test
    void applyQuote_unknownTickerDoesNotCallOnUpdate() {
        source.applyQuote("UNKNOWN", quoteNode(100.00, 0.50));

        assertThat(updates).isEmpty();
    }

    @Test
    void applyQuote_priceIsRoundedToTwoDecimalPlaces() {
        source.applyQuote("MSFT", quoteNode(415.555, 0.003));

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).price()).isEqualByComparingTo("415.56");
        assertThat(updates.get(0).totalChange()).isEqualByComparingTo("0.00");
    }

    // ── fetchInitialPrices (HTTP interaction) ────────────────────────────────

    @Test
    void fetchInitialPrices_sendsOneRequestPerTickerWithApiKey() throws InterruptedException {
        enqueueZeroQuotes(10);

        StepVerifier.create(source.fetchInitialPrices())
            .verifyComplete();

        assertThat(mockServer.getRequestCount()).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            RecordedRequest req = mockServer.takeRequest();
            assertThat(req.getPath())
                .startsWith("/quote")
                .contains("token=" + API_KEY);
        }
    }

    @Test
    void fetchInitialPrices_populatesPricesAndCallsOnUpdate() {
        enqueueQuote(182.50, 1.25);
        enqueueZeroQuotes(9);

        StepVerifier.create(source.fetchInitialPrices())
            .verifyComplete();

        assertThat(updates).anySatisfy(s -> {
            assertThat(s.price()).isEqualByComparingTo("182.50");
            assertThat(s.totalChange()).isEqualByComparingTo("1.25");
        });
    }

    @Test
    void fetchInitialPrices_doesNotCallOnUpdateForZeroPrice() {
        enqueueZeroQuotes(10);

        StepVerifier.create(source.fetchInitialPrices())
            .verifyComplete();

        assertThat(updates).isEmpty();
    }

    @Test
    void fetchInitialPrices_swallowsRestErrorAndCompletes() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));
        enqueueZeroQuotes(9);

        StepVerifier.create(source.fetchInitialPrices())
            .verifyComplete();
    }

    // ── handleMessage (WebSocket trade feed) ─────────────────────────────────

    @Test
    void handleMessage_tradeType_callsOnUpdateWithCorrectPrice() {
        seedPrice("AAPL", 150.00);

        source.handleMessage(tradeJson("AAPL", 151.25));

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).ticker()).isEqualTo("AAPL");
        assertThat(updates.get(0).price()).isEqualByComparingTo("151.25");
    }

    @Test
    void handleMessage_tradeType_changeIsTickToDeltaFromPreviousPrice() {
        seedPrice("MSFT", 400.00);

        source.handleMessage(tradeJson("MSFT", 402.50));

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).lastChange()).isEqualByComparingTo("2.50");
    }

    @Test
    void handleMessage_tradeType_negativeChangeWhenPriceFalls() {
        seedPrice("TSLA", 200.00);

        source.handleMessage(tradeJson("TSLA", 197.75));

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).lastChange()).isEqualByComparingTo("-2.25");
    }

    @Test
    void handleMessage_multipleTradesInOneMessage_callsOnUpdateForEach() {
        seedPrice("AAPL", 150.00);
        seedPrice("TSLA", 200.00);

        source.handleMessage(
            "{\"type\":\"trade\",\"data\":[" +
            "{\"s\":\"AAPL\",\"p\":151.00}," +
            "{\"s\":\"TSLA\",\"p\":199.50}]}"
        );

        assertThat(updates).hasSize(2);
        assertThat(updates).extracting(Stock::ticker)
            .containsExactlyInAnyOrder("AAPL", "TSLA");
    }

    @Test
    void handleMessage_pingType_isIgnored() {
        source.handleMessage("{\"type\":\"ping\"}");
        assertThat(updates).isEmpty();
    }

    @Test
    void handleMessage_unknownSymbol_doesNotCallOnUpdate() {
        source.handleMessage(tradeJson("UNKNOWN", 100.00));
        assertThat(updates).isEmpty();
    }

    @Test
    void handleMessage_emptyDataArray_doesNotCallOnUpdate() {
        source.handleMessage("{\"type\":\"trade\",\"data\":[]}");
        assertThat(updates).isEmpty();
    }

    @Test
    void handleMessage_malformedJson_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
            source.handleMessage("not-valid-{{json")
        );
        assertThat(updates).isEmpty();
    }

    // ── stop ─────────────────────────────────────────────────────────────────

    @Test
    void stop_isNoOpBeforeStart() {
        assertThatNoException().isThrownBy(() -> source.stop());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ObjectNode quoteNode(double price, double change) {
        return MAPPER.createObjectNode().put("c", price).put("d", change);
    }

    private void enqueueQuote(double price, double change) {
        mockServer.enqueue(new MockResponse()
            .setBody("{\"c\":" + price + ",\"d\":" + change + "}")
            .addHeader("Content-Type", "application/json"));
    }

    private void enqueueZeroQuotes(int count) {
        for (int i = 0; i < count; i++) {
            enqueueQuote(0, 0);
        }
    }

    private String tradeJson(String symbol, double price) {
        return "{\"type\":\"trade\",\"data\":[{\"s\":\"" + symbol + "\",\"p\":" + price + "}]}";
    }

    /**
     * Drives the in-memory stock price to a known value so subsequent handleMessage
     * assertions can check the tick-to-tick delta correctly.
     */
    private void seedPrice(String symbol, double price) {
        source.handleMessage(tradeJson(symbol, price));
        updates.clear();
    }

    private static void injectOnUpdate(FinnhubPriceSource target, Consumer<Stock> consumer)
            throws ReflectiveOperationException {
        Field field = FinnhubPriceSource.class.getDeclaredField("onUpdate");
        field.setAccessible(true);
        field.set(target, consumer);
    }
}
