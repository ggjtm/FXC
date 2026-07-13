package com.fxc.exchange.book;

import java.math.BigDecimal;

/**
 * A working order in the matching engine. Mutable: {@link #cumQty} and {@link #status} evolve as
 * the order fills. Asset-class agnostic — it carries only a {@code symbol}; the engine validates it
 * against the {@link com.fxc.common.instrument.Instrument} for that symbol.
 */
public final class Order {

    private final String orderId;
    private final String broker;      // FIX session / owning broker
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal limitPrice;   // null for MARKET orders
    private final BigDecimal quantity;
    private final long sequence;           // engine arrival sequence (time priority)

    private BigDecimal cumQty = BigDecimal.ZERO;
    private OrderStatus status = OrderStatus.NEW;

    public Order(String orderId, String broker, String symbol, Side side, OrderType type,
                 BigDecimal limitPrice, BigDecimal quantity, long sequence) {
        this.orderId = orderId;
        this.broker = broker;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.limitPrice = limitPrice;
        this.quantity = quantity;
        this.sequence = sequence;
    }

    public String orderId() {
        return orderId;
    }

    public String broker() {
        return broker;
    }

    public String symbol() {
        return symbol;
    }

    public Side side() {
        return side;
    }

    public OrderType type() {
        return type;
    }

    /** Limit price, or {@code null} for a market order. */
    public BigDecimal limitPrice() {
        return limitPrice;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public long sequence() {
        return sequence;
    }

    public BigDecimal cumQty() {
        return cumQty;
    }

    public OrderStatus status() {
        return status;
    }

    /** Quantity still open (quantity − cumQty). */
    public BigDecimal leavesQty() {
        return quantity.subtract(cumQty);
    }

    boolean isMarket() {
        return type == OrderType.MARKET;
    }

    /** Apply a fill of {@code fillQty}, advancing cumQty and status. */
    void fill(BigDecimal fillQty) {
        cumQty = cumQty.add(fillQty);
        int cmp = cumQty.compareTo(quantity);
        if (cmp >= 0) {
            status = OrderStatus.FILLED;
        } else {
            status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    void markCancelled() {
        status = OrderStatus.CANCELLED;
    }

    void markRejected() {
        status = OrderStatus.REJECTED;
    }

    @Override
    public String toString() {
        return "Order[" + orderId + " " + broker + " " + side + " " + type + " "
                + leavesQty() + "/" + quantity + " @" + limitPrice + " " + symbol
                + " " + status + "]";
    }
}
