package com.fxc.exchange.book;

import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;

/**
 * Pre-trade validation of an incoming order against its {@link Instrument} (tick/lot compliance).
 * Asset-class agnostic — reads only {@code tickSize} / {@code lotSize} from the instrument.
 */
public final class OrderValidation {

    private OrderValidation() {
    }

    /**
     * @return {@code null} if the order is valid, otherwise a human-readable rejection reason.
     */
    public static String validate(Instrument instrument, OrderType type, BigDecimal price, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return "quantity must be positive";
        }
        if (!isMultipleOf(quantity, instrument.lotSize())) {
            return "quantity " + quantity + " is not a multiple of lot size " + instrument.lotSize();
        }
        if (type == OrderType.LIMIT) {
            if (price == null || price.signum() <= 0) {
                return "limit price must be positive";
            }
            if (!isMultipleOf(price, instrument.tickSize())) {
                return "price " + price + " is not a multiple of tick size " + instrument.tickSize();
            }
        } else { // MARKET
            if (price != null) {
                return "market order must not carry a price";
            }
        }
        return null;
    }

    private static boolean isMultipleOf(BigDecimal value, BigDecimal increment) {
        return value.remainder(increment).compareTo(BigDecimal.ZERO) == 0;
    }
}
