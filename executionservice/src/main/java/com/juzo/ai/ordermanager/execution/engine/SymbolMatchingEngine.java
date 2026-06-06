package com.juzo.ai.ordermanager.execution.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

class SymbolMatchingEngine {

    private static final Logger log = LoggerFactory.getLogger(SymbolMatchingEngine.class);

    private final String ticker;
    private final LimitOrderBook book = new LimitOrderBook();

    SymbolMatchingEngine(String ticker) {
        this.ticker = ticker;
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
        } else {
            RestingOrder resting = new RestingOrder(
                    event.orderId, event.side, event.quantity, event.quantity,
                    event.limitPrice, event.timestampNanos);
            book.add(resting);
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
        } else {
            log.info("NO_LIQUIDITY ticker={} orderId={} side={} — no resting orders on opposite side",
                    ticker, event.orderId, event.side);
        }
    }

    private void cancelOrder(InboundEvent event) {
        book.cancel(event.orderId);
        log.info("CANCELLED ticker={} orderId={} bids={} asks={}",
                ticker, event.orderId, book.bidCount(), book.askCount());
    }
}
