package com.juzo.ai.ordermanager.entity;

public record MarketStatus(String exchange, String holiday, boolean isOpen, String session, String timezone, long t) {}
