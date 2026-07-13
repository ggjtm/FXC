package com.fxc.broker.model;

import java.math.BigDecimal;

/**
 * A position in the unified model (docs/DESIGN.md §3.0): {@code (account, instrument|currency,
 * quantity)} discriminated by {@link HoldingType}. Cash balances and share positions share this
 * shape. Mutable.
 *
 * @param account    account number
 * @param instrument currency code (CASH) or equity symbol (SHARE)
 */
public final class Position {

    private final String account;
    private final String instrument;
    private final HoldingType holdingType;
    private BigDecimal quantity;
    private BigDecimal avgPrice;   // meaningful for SHARE; 0 for CASH

    public Position(String account, String instrument, HoldingType holdingType,
                    BigDecimal quantity, BigDecimal avgPrice) {
        this.account = account;
        this.instrument = instrument;
        this.holdingType = holdingType;
        this.quantity = quantity;
        this.avgPrice = avgPrice;
    }

    public String account() { return account; }
    public String instrument() { return instrument; }
    public HoldingType holdingType() { return holdingType; }
    public BigDecimal quantity() { return quantity; }
    public BigDecimal avgPrice() { return avgPrice; }

    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }

    /** Stable key for a position within an account. */
    public String key() {
        return account + "|" + holdingType + "|" + instrument;
    }

    public static String keyOf(String account, HoldingType type, String instrument) {
        return account + "|" + type + "|" + instrument;
    }
}
