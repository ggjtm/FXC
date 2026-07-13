package com.fxc.investor.strategy;

import java.math.BigDecimal;

/**
 * A strategy's decision to trade: which side, at what limit price, for how many units. The price is
 * raw (the agent snaps it to the instrument tick size before submitting).
 */
public record OrderIntent(Side side, BigDecimal price, BigDecimal quantity) {
}
