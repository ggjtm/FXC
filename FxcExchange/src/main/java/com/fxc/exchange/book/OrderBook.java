package com.fxc.exchange.book;

import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A price-time-priority limit order book for a single instrument. Pure domain logic — no GridGain,
 * no FIX — so it can be unit-tested exhaustively (the highest-value test target in the system,
 * per PLAN Phase 1).
 *
 * <p>Matching rules:
 * <ul>
 *   <li>Best price first; within a price level, first-in-first-out (time priority).</li>
 *   <li>Executions occur at the <b>resting</b> order's price (price improvement accrues to the
 *       incoming aggressor).</li>
 *   <li>A LIMIT order's unfilled remainder rests in the book.</li>
 *   <li>A MARKET order never rests: any unfilled remainder is cancelled (immediate-or-cancel).</li>
 * </ul>
 *
 * <p>Not thread-safe: the engine serializes access per instrument.
 * <p>ToDo: self-trade prevention (a broker crossing its own resting order) is intentionally not
 * implemented yet — see {@code docs/DESIGN.md} §6 (auth/realism).
 */
public final class OrderBook {

    private final Instrument instrument;
    private final LongSupplier tradeSeq;

    /** Buy side: highest price first. */
    private final NavigableMap<BigDecimal, Deque<Order>> bids = new TreeMap<>(Comparator.reverseOrder());
    /** Sell side: lowest price first. */
    private final NavigableMap<BigDecimal, Deque<Order>> asks = new TreeMap<>();
    /** Resting orders by id, for O(1) cancel. */
    private final Map<String, Order> resting = new HashMap<>();

    /** Book with a private sequence source — convenient for standalone tests. */
    public OrderBook(Instrument instrument) {
        this(instrument, new AtomicLong()::incrementAndGet);
    }

    /** Book sharing an engine-wide monotonic sequence source (trade ids / audit ordering). */
    public OrderBook(Instrument instrument, LongSupplier tradeSeq) {
        this.instrument = instrument;
        this.tradeSeq = tradeSeq;
    }

    public Instrument instrument() {
        return instrument;
    }

    /**
     * Match an incoming order against the opposite side, then rest any LIMIT remainder.
     * The order's {@code cumQty}/{@code status} are mutated in place; resting orders it fills are
     * mutated too. Assumes the order has already passed {@link OrderValidation}.
     *
     * @return the trades generated, in execution order (possibly empty)
     */
    public List<Trade> submit(Order order) {
        List<Trade> trades = new ArrayList<>();
        NavigableMap<BigDecimal, Deque<Order>> opposite = (order.side() == Side.BUY) ? asks : bids;

        while (order.leavesQty().signum() > 0 && !opposite.isEmpty()) {
            Map.Entry<BigDecimal, Deque<Order>> bestLevel = opposite.firstEntry();
            BigDecimal restPrice = bestLevel.getKey();
            if (!crosses(order, restPrice)) {
                break;
            }
            Deque<Order> queue = bestLevel.getValue();
            while (order.leavesQty().signum() > 0 && !queue.isEmpty()) {
                Order restingOrder = queue.peekFirst();
                BigDecimal fillQty = order.leavesQty().min(restingOrder.leavesQty());

                order.fill(fillQty);
                restingOrder.fill(fillQty);
                trades.add(buildTrade(order, restingOrder, restPrice, fillQty));

                if (restingOrder.leavesQty().signum() == 0) {
                    queue.pollFirst();
                    resting.remove(restingOrder.orderId());
                }
            }
            if (queue.isEmpty()) {
                opposite.remove(restPrice);
            }
        }

        if (order.leavesQty().signum() > 0) {
            if (order.isMarket()) {
                order.markCancelled(); // IOC: unfilled market remainder is cancelled
            } else {
                restOrder(order);
            }
        }
        return trades;
    }

    /**
     * Cancel a resting order.
     *
     * @return the cancelled order, or empty if it is not resting (unknown, already filled, or
     *         already cancelled)
     */
    public Optional<Order> cancel(String orderId) {
        Order order = resting.remove(orderId);
        if (order == null) {
            return Optional.empty();
        }
        NavigableMap<BigDecimal, Deque<Order>> side = (order.side() == Side.BUY) ? bids : asks;
        BigDecimal price = order.limitPrice();
        Deque<Order> queue = side.get(price);
        if (queue != null) {
            queue.remove(order);
            if (queue.isEmpty()) {
                side.remove(price);
            }
        }
        order.markCancelled();
        return Optional.of(order);
    }

    // --- Market-data views ---

    public Optional<BigDecimal> bestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    public Optional<BigDecimal> bestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    /** Aggregated buy-side levels, best first, up to {@code depth}. */
    public List<Level> bidLevels(int depth) {
        return levels(bids, depth);
    }

    /** Aggregated sell-side levels, best first, up to {@code depth}. */
    public List<Level> askLevels(int depth) {
        return levels(asks, depth);
    }

    /** One aggregated price level. */
    public record Level(BigDecimal price, BigDecimal quantity) {
    }

    // --- internals ---

    private boolean crosses(Order taker, BigDecimal restPrice) {
        if (taker.isMarket()) {
            return true;
        }
        return taker.side() == Side.BUY
                ? taker.limitPrice().compareTo(restPrice) >= 0
                : taker.limitPrice().compareTo(restPrice) <= 0;
    }

    private Trade buildTrade(Order taker, Order restingOrder, BigDecimal price, BigDecimal qty) {
        long seq = tradeSeq.getAsLong();
        String tradeId = instrument.symbol() + "#" + seq;
        Order buy = taker.side() == Side.BUY ? taker : restingOrder;
        Order sell = taker.side() == Side.BUY ? restingOrder : taker;
        return new Trade(tradeId, instrument.symbol(), price, qty,
                buy.orderId(), sell.orderId(), buy.broker(), sell.broker(),
                taker.side(), seq);
    }

    private void restOrder(Order order) {
        NavigableMap<BigDecimal, Deque<Order>> side = (order.side() == Side.BUY) ? bids : asks;
        side.computeIfAbsent(order.limitPrice(), p -> new ArrayDeque<>()).addLast(order);
        resting.put(order.orderId(), order);
    }

    private static List<Level> levels(NavigableMap<BigDecimal, Deque<Order>> side, int depth) {
        List<Level> out = new ArrayList<>();
        for (Map.Entry<BigDecimal, Deque<Order>> e : side.entrySet()) {
            if (out.size() >= depth) {
                break;
            }
            BigDecimal qty = BigDecimal.ZERO;
            for (Order o : e.getValue()) {
                qty = qty.add(o.leavesQty());
            }
            out.add(new Level(e.getKey(), qty));
        }
        return out;
    }
}
