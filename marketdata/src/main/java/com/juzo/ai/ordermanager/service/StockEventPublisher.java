package com.juzo.ai.ordermanager.service;

import com.juzo.ai.ordermanager.entity.Stock;

public interface StockEventPublisher {
    void publish(Stock stock);
}
