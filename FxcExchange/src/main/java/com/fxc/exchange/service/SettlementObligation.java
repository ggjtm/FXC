package com.fxc.exchange.service;

import java.math.BigDecimal;

/**
 * A netted settlement obligation for one broker in one instrument for one clearing cycle
 * (docs/DESIGN.md §4.1). Amounts are signed from the broker's perspective (positive = receives,
 * negative = delivers). Shape is asset-class agnostic; {@code settleStyle} records how it was
 * derived from the instrument's {@link com.fxc.common.instrument.SettlementProfile}.
 *
 * <ul>
 *   <li><b>FX (CURRENCY_EXCHANGE):</b> {@code quantity}/{@code receiveCcy} carry the net base
 *       currency; {@code deliverCcy}/{@code deliverAmount} carry the net quote currency.</li>
 *   <li><b>Equity (DELIVERY_VERSUS_PAYMENT):</b> {@code quantity} is net shares;
 *       {@code deliverCcy}/{@code deliverAmount} carry the net cash; {@code receiveCcy} is null.</li>
 * </ul>
 */
public record SettlementObligation(
        String id,
        long cycle,
        String broker,
        String symbol,
        String settleStyle,
        String deliverCcy,
        BigDecimal deliverAmount,
        String receiveCcy,
        BigDecimal receiveAmount,
        BigDecimal quantity,
        int settleLag) {
}
