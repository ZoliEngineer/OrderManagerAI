package com.juzo.ai.ordermanager.execution.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

class SymbolMatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(SymbolMatchingEngine.class);

    private final String ticker;
    private final LimitOrderBook book = new LimitOrderBook();

    private final Counter matchesLimit;
    private final Counter matchesMarket;
    private final Counter noLiquidity;
    private final Counter resting;
    private final Counter cancels;

    SymbolMatchingEngine(String ticker, MeterRegistry meters) {
        this.ticker = ticker;
        // Created once per ticker (a small fixed set) and kept as fields: the hot path only increments.
        this.matchesLimit = counter(meters, "execution.matches", "order_type", "limit");
        this.matchesMarket = counter(meters, "execution.matches", "order_type", "market");
        this.noLiquidity = counter(meters, "execution.no_liquidity", "order_type", "market");
        this.resting = counter(meters, "execution.resting", "order_type", "limit");
        this.cancels = counter(meters, "execution.cancels", "order_type", "any");
    }

    private Counter counter(MeterRegistry meters, String name, String tagKey, String tagValue) {
        return Counter.builder(name).tag("ticker", ticker).tag(tagKey, tagValue).register(meters);
    }

    void onEvent(InboundEvent event) {
        switch (event.type) {
            case NEW_ORDER    -> processNewOrder(event);
            case CANCEL_ORDER -> cancelOrder(event);
            case MARKET_TICK  -> { /* sweep resting limits — phase 2 */ }
        }
    }

    private void processNewOrder(InboundEvent event) {
        if (event.orderType == OrderType.MARKET) {
            processMarketOrder(event);
        } else {
            processLimitOrder(event);
        }
    }

    private void processLimitOrder(InboundEvent event) {
        Optional<RestingOrder> match = book.tryMatch(event.side, event.limitPrice);
        if (match.isPresent()) {
            RestingOrder resting = match.get();
            log.info("MATCH ticker={} incoming={} resting={} price={}",
                    ticker, event.orderId, resting.orderId(), resting.limitPrice());
            book.cancel(resting.orderId());
            matchesLimit.increment();
        } else {
            RestingOrder resting = new RestingOrder(
                    event.orderId, event.side, event.quantity, event.quantity,
                    event.limitPrice, event.timestampNanos);
            book.add(resting);
            this.resting.increment();
            log.debug("RESTING ticker={} side={} orderId={} bids={} asks={}",
                    ticker, event.side, event.orderId, book.bidCount(), book.askCount());
        }
    }

    private void processMarketOrder(InboundEvent event) {
        Optional<RestingOrder> match = book.tryMatch(event.side, null);
        if (match.isPresent()) {
            RestingOrder resting = match.get();
            log.info("MATCH ticker={} incoming={} resting={} price={} venue=INTERNAL",
                    ticker, event.orderId, resting.orderId(), resting.limitPrice());
            book.cancel(resting.orderId());
            matchesMarket.increment();
        } else {
            noLiquidity.increment();
            log.info("NO_LIQUIDITY ticker={} orderId={} side={} — no resting orders on opposite side",
                    ticker, event.orderId, event.side);
        }
    }

    private void cancelOrder(InboundEvent event) {
        book.cancel(event.orderId);
        cancels.increment();
        log.info("CANCELLED ticker={} orderId={} bids={} asks={}",
                ticker, event.orderId, book.bidCount(), book.askCount());
    }
}
