package com.fxc.exchange.grid;

import com.fxc.common.instrument.AssetClass;
import com.fxc.common.instrument.EquityInstrument;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import com.fxc.exchange.book.Order;
import com.fxc.exchange.book.Trade;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/**
 * Writes exchange hot state into the GridGain SQL tables ({@link ExchangeTables}). Upserts for
 * mutable rows (INSTRUMENT, ORDERS), inserts for append-only rows (TRADE, SETTLEMENT_OBLIGATION).
 */
public final class ExchangeRepository {

    private final IgniteCache<?, ?> sql;

    public ExchangeRepository(Ignite ignite) {
        this.sql = ignite.getOrCreateCache("fxc-sql-entry");
    }

    public void upsertInstrument(Instrument instrument) {
        String baseCcy = instrument instanceof FxSpotInstrument fx ? fx.baseCurrency().getCurrencyCode() : null;
        String issuer = instrument instanceof EquityInstrument eq ? eq.issuerName() : null;
        run("MERGE INTO INSTRUMENT (symbol, asset_class, base_ccy, quote_ccy, issuer, tick_size, lot_size, settle_style, settle_lag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                instrument.symbol(),
                instrument.assetClass().name(),
                baseCcy,
                instrument.quoteCurrency().getCurrencyCode(),
                issuer,
                instrument.tickSize(),
                instrument.lotSize(),
                instrument.settlement().style().name(),
                instrument.settlement().settlementLagDays());
    }

    public void upsertOrder(Order order) {
        run("MERGE INTO ORDERS (order_id, broker, symbol, side, order_type, limit_price, quantity, cum_qty, status, sequence) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.orderId(),
                order.broker(),
                order.symbol(),
                order.side().name(),
                order.type().name(),
                order.limitPrice(),
                order.quantity(),
                order.cumQty(),
                order.status().name(),
                order.sequence());
    }

    public void insertTrade(Trade trade) {
        run("INSERT INTO TRADE (trade_id, symbol, price, quantity, buy_order_id, sell_order_id, buy_broker, sell_broker, aggressor, sequence) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                trade.tradeId(),
                trade.symbol(),
                trade.price(),
                trade.quantity(),
                trade.buyOrderId(),
                trade.sellOrderId(),
                trade.buyBroker(),
                trade.sellBroker(),
                trade.aggressorSide().name(),
                trade.sequence());
    }

    public void insertObligation(com.fxc.exchange.service.SettlementObligation o) {
        run("INSERT INTO SETTLEMENT_OBLIGATION (id, cycle, broker, symbol, settle_style, deliver_ccy, deliver_amount, receive_ccy, receive_amount, quantity, settle_lag) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                o.id(), o.cycle(), o.broker(), o.symbol(), o.settleStyle(),
                o.deliverCcy(), o.deliverAmount(), o.receiveCcy(), o.receiveAmount(),
                o.quantity(), o.settleLag());
    }

    public boolean isFx(String symbol) {
        var rows = sql.query(new SqlFieldsQuery(
                "SELECT asset_class FROM INSTRUMENT WHERE symbol = ?").setArgs(symbol)).getAll();
        return !rows.isEmpty() && AssetClass.FX_SPOT.name().equals(rows.get(0).get(0));
    }

    private void run(String dml, Object... args) {
        sql.query(new SqlFieldsQuery(dml).setArgs(args)).getAll();
    }
}
