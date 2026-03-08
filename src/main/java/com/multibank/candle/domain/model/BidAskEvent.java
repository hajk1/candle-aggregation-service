package com.multibank.candle.domain.model;

/**
 * Immutable market data tick. {@code timestamp} is UNIX seconds.
 * Mid-price = (bid + ask) / 2 is derived by aggregation logic, not stored here.
 */
public record BidAskEvent(String symbol, double bid, double ask, long timestamp) {}
