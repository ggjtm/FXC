package com.fxc.broker.model;

import java.math.BigDecimal;

/**
 * A client order at the broker (docs/DESIGN.md §4.2). Mutable: state advances as
 * {@code ExecutionReport}s arrive from the exchange.
 */
public final class ClientOrder {

    private final String clientOrderId;   // ClOrdID / OFX TRNUID
    private final String account;
    private final String symbol;
    private final Side side;
    private final OrderType type;
    private final BigDecimal limitPrice;   // null for market
    private final BigDecimal quantity;

    private BigDecimal cumQty = BigDecimal.ZERO;
    private BigDecimal avgPrice = BigDecimal.ZERO;
    private OrderStatus status = OrderStatus.NEW;
    private String exchangeOrderId;        // OrderID(37) assigned by the exchange
    private String rejectReason;

    public ClientOrder(String clientOrderId, String account, String symbol, Side side,
                       OrderType type, BigDecimal limitPrice, BigDecimal quantity) {
        this.clientOrderId = clientOrderId;
        this.account = account;
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.limitPrice = limitPrice;
        this.quantity = quantity;
    }

    public String clientOrderId() { return clientOrderId; }
    public String account() { return account; }
    public String symbol() { return symbol; }
    public Side side() { return side; }
    public OrderType type() { return type; }
    public BigDecimal limitPrice() { return limitPrice; }
    public BigDecimal quantity() { return quantity; }
    public BigDecimal cumQty() { return cumQty; }
    public BigDecimal avgPrice() { return avgPrice; }
    public OrderStatus status() { return status; }
    public String exchangeOrderId() { return exchangeOrderId; }
    public String rejectReason() { return rejectReason; }

    public void setStatus(OrderStatus status) { this.status = status; }
    public void setExchangeOrderId(String exchangeOrderId) { this.exchangeOrderId = exchangeOrderId; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    /** Fold a fill into the running cumulative quantity and volume-weighted average price. */
    public void applyFill(BigDecimal lastQty, BigDecimal lastPx) {
        BigDecimal newCum = cumQty.add(lastQty);
        if (newCum.signum() > 0) {
            avgPrice = cumQty.multiply(avgPrice).add(lastQty.multiply(lastPx))
                    .divide(newCum, 8, java.math.RoundingMode.HALF_UP);
        }
        cumQty = newCum;
        status = cumQty.compareTo(quantity) >= 0 ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }
}
