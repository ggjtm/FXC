package com.fxc.broker.model;

/** Lifecycle state of a client order at the broker. */
public enum OrderStatus {
    NEW,            // accepted by the broker, not yet acknowledged by the exchange
    ROUTED,         // sent to the exchange
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED
}
