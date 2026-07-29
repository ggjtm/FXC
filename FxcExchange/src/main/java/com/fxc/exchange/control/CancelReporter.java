package com.fxc.exchange.control;

import com.fxc.exchange.book.Order;

/**
 * Reports an administratively cancelled order back to the broker that owns it.
 *
 * <p>This exists because "clear the order book" (docs/DESIGN.md §6) is not just a book mutation:
 * every broker's OMS tracks its own order state from {@code ExecutionReport}s, so orders that vanish
 * from the exchange without a {@code CANCELED} report would leave every connected broker believing
 * it still has live orders. Implemented by {@code ExchangeApplication} over FIX; a recording fake
 * stands in for it in tests, the same way {@code MarketDataPublisher} is faked.
 */
public interface CancelReporter {

    /** A no-op reporter, for wiring where no FIX acceptor is attached. */
    CancelReporter NONE = order -> {
    };

    void reportCancelled(Order order);
}
