package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;

import java.util.List;
import java.util.function.Consumer;

public interface PriceUpdateSource {
    List<Stock> initialStocks();
    void start(Consumer<Stock> onUpdate);
    void stop();
}
