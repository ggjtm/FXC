package com.fxc.exchange.service;

/** A downstream consumer of {@link ExchangeEvent}s (market data, clearing). */
@FunctionalInterface
public interface ExchangeListener {
    void onEvent(ExchangeEvent event);
}
