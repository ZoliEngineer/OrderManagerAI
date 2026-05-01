package com.juzo.ai.ordermanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.juzo.ai.ordermanager.entity.Stock;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
@ConditionalOnProperty(name = "finnhub.api-key")
public class FinnhubPriceSource implements PriceUpdateSource {

    private static final Logger log = LoggerFactory.getLogger(FinnhubPriceSource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // Finnhub REST /quote field names
    private static final String FIELD_CURRENT_PRICE = "c";
    private static final String FIELD_DAY_CHANGE    = "d";

    // Finnhub WebSocket message field names
    private static final String FIELD_MSG_TYPE   = "type";
    private static final String FIELD_MSG_DATA   = "data";
    private static final String FIELD_TRADE_TYPE = "trade";
    private static final String FIELD_SYMBOL     = "s";
    private static final String FIELD_PRICE      = "p";

    private final String apiKey;
    private final String wsUrl;
    private final WebClient webClient;
    private final ReactorNettyWebSocketClient wsClient;
    private final ConcurrentHashMap<String, Stock> stocks = new ConcurrentHashMap<>();

    private Consumer<Stock> onUpdate;
    private Disposable connection;

    public FinnhubPriceSource(
            @Value("${finnhub.api-key}") String apiKey,
            @Value("${finnhub.ws-url}") String wsUrl,
            WebClient finnhubRestClient,
            ReactorNettyWebSocketClient finnhubWsClient) {
        this.apiKey = apiKey;
        this.wsUrl = wsUrl;
        this.webClient = finnhubRestClient;
        this.wsClient = finnhubWsClient;
        defaultStocks().forEach(s -> stocks.put(s.ticker(), s));
    }

    @Override
    public List<Stock> initialStocks() {
        return new ArrayList<>(stocks.values());
    }

    @Override
    public void start(Consumer<Stock> onUpdate) {
        this.onUpdate = onUpdate;
        fetchInitialPrices()
            .doOnTerminate(this::connect)
            .subscribe();
    }

    @Override
    public void stop() {
        if (connection != null) {
            connection.dispose();
        }
    }

    Mono<Void> fetchInitialPrices() {
        return Flux.fromIterable(stocks.keySet())
            .flatMap(ticker -> webClient.get()
                .uri("/quote?symbol={symbol}", ticker)
                .retrieve()
                .bodyToMono(String.class)
                .mapNotNull(json -> {
                    try {
                        return OBJECT_MAPPER.readTree(json);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse quote JSON for {}: {}", ticker, e.getMessage());
                        return null;
                    }
                })
                .doOnNext(node -> applyQuote(ticker, node))
                .onErrorResume(e -> {
                    log.warn("Failed to fetch initial quote for {}: {}", ticker, e.getMessage());
                    return Mono.empty();
                })
            )
            .then();
    }

    void applyQuote(String ticker, JsonNode node) {
        double rawPrice = node.path(FIELD_CURRENT_PRICE).asDouble();
        if (rawPrice == 0) {
            log.debug("No current price for {} (market may be closed)", ticker);
            return;
        }
        BigDecimal price  = BigDecimal.valueOf(rawPrice)
                                      .setScale(2, RoundingMode.HALF_UP);
        BigDecimal change = BigDecimal.valueOf(node.path(FIELD_DAY_CHANGE).asDouble())
                                      .setScale(2, RoundingMode.HALF_UP);

        Stock updated = stocks.compute(ticker, (key, existing) ->
            existing == null ? null : new Stock(existing.ticker(), existing.name(), price, change)
        );
        if (updated != null) {
            onUpdate.accept(updated);
        }
    }

    private void connect() {
        // Finnhub WebSocket API requires the token as a query parameter; header auth is not supported.
        // Do not log this URI — it contains the API key.
        URI uri = URI.create(wsUrl + "?token=" + apiKey);

        connection = wsClient.execute(uri, session -> {
            Mono<Void> sendSubscriptions = session.send(
                Flux.fromIterable(stocks.keySet())
                    .map(ticker -> session.textMessage(subscribeMessage(ticker)))
            );

            Mono<Void> receiveUpdates = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(this::handleMessage)
                .then();

            return Mono.when(sendSubscriptions, receiveUpdates);
        })
        .doOnError(e -> log.error("Finnhub WebSocket error: {}", e.getMessage()))
        .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
            .maxBackoff(Duration.ofSeconds(60))
            .doBeforeRetry(sig -> log.warn("Reconnecting to Finnhub (attempt {})", sig.totalRetries() + 1)))
        .subscribe();
    }

    void handleMessage(String message) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(message);
            if (!FIELD_TRADE_TYPE.equals(root.path(FIELD_MSG_TYPE).asText())) {
                return;
            }
            for (JsonNode trade : root.path(FIELD_MSG_DATA)) {
                processTradeUpdate(trade.path(FIELD_SYMBOL).asText(),
                                   trade.path(FIELD_PRICE).asDouble());
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Finnhub message: {}", e.getMessage());
        }
    }

    private void processTradeUpdate(String symbol, double rawPrice) {
        BigDecimal newPrice = BigDecimal.valueOf(rawPrice).setScale(2, RoundingMode.HALF_UP);
        Stock updated = stocks.compute(symbol, (key, existing) -> {
            if (existing == null) return null;
            BigDecimal change = newPrice.subtract(existing.price()).setScale(2, RoundingMode.HALF_UP);
            return new Stock(existing.ticker(), existing.name(), newPrice, change);
        });
        if (updated != null) {
            onUpdate.accept(updated);
        }
    }

    private static String subscribeMessage(String ticker) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("type", "subscribe", "symbol", ticker));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize subscribe message for " + ticker, e);
        }
    }

    private static List<Stock> defaultStocks() {
        return List.of(
            new Stock("AAPL",  "Apple Inc.",            BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("MSFT",  "Microsoft Corp.",       BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("NVDA",  "NVIDIA Corp.",          BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("AMZN",  "Amazon.com Inc.",       BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("GOOGL", "Alphabet Inc.",         BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("META",  "Meta Platforms Inc.",   BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("BRK.B", "Berkshire Hathaway B",  BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("LLY",   "Eli Lilly and Co.",     BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("JPM",   "JPMorgan Chase & Co.",  BigDecimal.ZERO, BigDecimal.ZERO),
            new Stock("TSLA",  "Tesla Inc.",            BigDecimal.ZERO, BigDecimal.ZERO)
        );
    }
}
