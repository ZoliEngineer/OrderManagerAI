package com.juzo.ai.ordermanager.execution.engine;

import java.math.BigDecimal;
import java.util.*;

final class LimitOrderBook {

    // BUY side — highest price first
    private final NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> bids =
            new TreeMap<>(Comparator.reverseOrder());
    // SELL side — lowest price first
    private final NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> asks =
            new TreeMap<>();
    // orderId → order for O(1) cancel; also the canonical set of live orders
    private final Map<UUID, RestingOrder> index = new HashMap<>();

    /**
     * Returns the best-priced resting order that crosses the incoming price,
     * or empty if no match. {@code incomingPrice} null means MARKET (matches any).
     * Does not remove the matched order — caller must call {@link #cancel} after logging.
     */
    Optional<RestingOrder> tryMatch(Side incomingSide, BigDecimal incomingPrice) {
        NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> opposite =
                incomingSide == Side.BUY ? asks : bids;

        while (!opposite.isEmpty()) {
            Map.Entry<BigDecimal, ArrayDeque<RestingOrder>> best = opposite.firstEntry();
            BigDecimal bestPrice = best.getKey();

            boolean priceMatches = incomingPrice == null
                    || (incomingSide == Side.BUY
                            ? bestPrice.compareTo(incomingPrice) <= 0   // ask <= buyLimit
                            : bestPrice.compareTo(incomingPrice) >= 0); // bid >= sellLimit

            if (!priceMatches) return Optional.empty();

            ArrayDeque<RestingOrder> level = best.getValue();
            while (!level.isEmpty()) {
                RestingOrder candidate = level.peekFirst();
                if (index.containsKey(candidate.orderId())) {
                    return Optional.of(candidate);
                }
                level.pollFirst(); // lazily skip cancelled orders
            }
            opposite.remove(bestPrice); // level drained, clean up
        }
        return Optional.empty();
    }

    void add(RestingOrder order) {
        NavigableMap<BigDecimal, ArrayDeque<RestingOrder>> side =
                order.side() == Side.BUY ? bids : asks;
        side.computeIfAbsent(order.limitPrice(), k -> new ArrayDeque<>()).addLast(order);
        index.put(order.orderId(), order);
    }

    /** Lazy removal: removes from the live index; deque entry is skipped on next {@link #tryMatch}. */
    void cancel(UUID orderId) {
        index.remove(orderId);
    }

    int bidCount() {
        return (int) index.values().stream().filter(o -> o.side() == Side.BUY).count();
    }

    int askCount() {
        return (int) index.values().stream().filter(o -> o.side() == Side.SELL).count();
    }
}
