package com.fxc.broker.model;

/**
 * Discriminator for the unified {@code POSITION} model (docs/DESIGN.md §3.0): a position is either
 * a currency cash balance or a share position.
 */
public enum HoldingType {
    /** A cash balance; the position's instrument key is a currency code (e.g. "USD"). */
    CASH,
    /** A share position; the position's instrument key is an equity symbol (e.g. "ACME"). */
    SHARE
}
