package com.fxc.common.instrument;

/**
 * How fills in an instrument become settlement obligations. This is deliberately a data-carrying
 * profile, not behavior: {@code fxc-common} holds no business logic (see {@code docs/DESIGN.md}
 * §5). {@code ClearingService} (FxcExchange, Phase 1) dispatches on {@link #style()} to turn a
 * trade into {@code SETTLEMENT_OBLIGATION} rows, so clearing stays asset-class agnostic.
 *
 * @param style             the settlement mechanics
 * @param settlementLagDays the {@code T+n} convention (business-day lag from trade to settlement)
 */
public record SettlementProfile(SettlementStyle style, int settlementLagDays) {

    public SettlementProfile {
        if (style == null) {
            throw new IllegalArgumentException("style must not be null");
        }
        if (settlementLagDays < 0) {
            throw new IllegalArgumentException("settlementLagDays must not be negative");
        }
    }

    /** FX spot: bilateral currency exchange, T+2 convention. */
    public static final SettlementProfile FX_SPOT_DEFAULT =
            new SettlementProfile(SettlementStyle.CURRENCY_EXCHANGE, 2);

    /** Cash equity: delivery-versus-payment, T+1 convention. */
    public static final SettlementProfile EQUITY_DEFAULT =
            new SettlementProfile(SettlementStyle.DELIVERY_VERSUS_PAYMENT, 1);

    /** The settlement mechanics a {@link SettlementProfile} selects. */
    public enum SettlementStyle {
        /** Two currency balances move against each other (FX spot). */
        CURRENCY_EXCHANGE,
        /** A share position moves against a cash balance (equities). */
        DELIVERY_VERSUS_PAYMENT;
        // ToDo (docs/DESIGN.md §6.3): margining / mark-to-market styles for derivatives
    }
}
