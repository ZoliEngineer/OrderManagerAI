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
    private static final String FIELD_CURRENT_PRICE  = "c";
    private static final String FIELD_DAY_CHANGE     = "d";
    private static final String FIELD_CHANGE_PERCENT = "dp";
    private static final String FIELD_HIGH           = "h";
    private static final String FIELD_LOW            = "l";
    private static final String FIELD_OPEN           = "o";
    private static final String FIELD_PREV_CLOSE     = "pc";

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

    private volatile Consumer<Stock> onUpdate;
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
            .concatMap(ticker -> webClient.get()
                .uri("/quote?symbol={symbol}&token={token}", ticker, apiKey)
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
        BigDecimal price         = bd(rawPrice);
        BigDecimal openPrice     = bd(node.path(FIELD_OPEN).asDouble());
        BigDecimal highPrice     = bd(node.path(FIELD_HIGH).asDouble());
        BigDecimal lowPrice      = bd(node.path(FIELD_LOW).asDouble());
        BigDecimal prevClose     = bd(node.path(FIELD_PREV_CLOSE).asDouble());
        BigDecimal totalChange   = bd(node.path(FIELD_DAY_CHANGE).asDouble());
        BigDecimal changePercent = bd(node.path(FIELD_CHANGE_PERCENT).asDouble());

        Stock updated = stocks.compute(ticker, (key, existing) ->
            existing == null ? null : new Stock(
                existing.ticker(), existing.name(),
                price, openPrice, highPrice, lowPrice, prevClose,
                totalChange, changePercent, BigDecimal.ZERO)
        );
        if (updated != null) {
            onUpdate.accept(updated);
        }
    }

    private static BigDecimal bd(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private void connect() {
        // Finnhub WebSocket API requires the token as a query parameter; header auth is not supported.
        // Do not log this URI — it contains the API key.
        URI uri = URI.create(wsUrl + "?token=" + apiKey);

        connection = wsClient.execute(uri, session ->
            session.send(
                Flux.fromIterable(stocks.keySet())
                    .map(ticker -> session.textMessage(subscribeMessage(ticker)))
            )
            .thenMany(session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .doOnNext(this::handleMessage))
            .then()
        )
        .doOnError(e -> log.error("Finnhub WebSocket error: {}", sanitizeMessage(e.getMessage())))
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
        BigDecimal newPrice = bd(rawPrice);
        Stock updated = stocks.compute(symbol, (key, existing) -> {
            if (existing == null) return null;
            BigDecimal lastChange   = newPrice.subtract(existing.price()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalChange  = existing.prevClose().compareTo(BigDecimal.ZERO) != 0
                ? newPrice.subtract(existing.prevClose()).setScale(2, RoundingMode.HALF_UP)
                : existing.totalChange();
            BigDecimal changePercent = existing.prevClose().compareTo(BigDecimal.ZERO) != 0
                ? newPrice.subtract(existing.prevClose())
                          .divide(existing.prevClose(), 4, RoundingMode.HALF_UP)
                          .multiply(BigDecimal.valueOf(100))
                          .setScale(2, RoundingMode.HALF_UP)
                : existing.changePercent();
            return new Stock(
                existing.ticker(), existing.name(),
                newPrice, existing.openPrice(), existing.highPrice(), existing.lowPrice(), existing.prevClose(),
                totalChange, changePercent, lastChange);
        });
        if (updated != null) {
            onUpdate.accept(updated);
        }
    }

    private static String sanitizeMessage(String message) {
        if (message == null) return null;
        return message.replaceAll("token=[^&\\s]+", "token=***");
    }

    private static String subscribeMessage(String ticker) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("type", "subscribe", "symbol", ticker));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize subscribe message for " + ticker, e);
        }
    }

    private static List<Stock> defaultStocks() {
        BigDecimal z = BigDecimal.ZERO;
        return List.of(
            new Stock("AAPL",  "Apple Inc.",            z, z, z, z, z, z, z, z),
            new Stock("MSFT",  "Microsoft Corp.",       z, z, z, z, z, z, z, z),
            new Stock("NVDA",  "NVIDIA Corp.",          z, z, z, z, z, z, z, z),
            new Stock("AMZN",  "Amazon.com Inc.",       z, z, z, z, z, z, z, z),
            new Stock("GOOGL", "Alphabet Inc.",         z, z, z, z, z, z, z, z),
            new Stock("META",  "Meta Platforms Inc.",   z, z, z, z, z, z, z, z),
            new Stock("BRK.B", "Berkshire Hathaway B",  z, z, z, z, z, z, z, z),
            new Stock("LLY",   "Eli Lilly and Co.",     z, z, z, z, z, z, z, z),
            new Stock("JPM",   "JPMorgan Chase & Co.",  z, z, z, z, z, z, z, z),
            new Stock("TSLA",  "Tesla Inc.",            z, z, z, z, z, z, z, z)
        );
    }
}
