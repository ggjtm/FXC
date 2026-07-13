package com.fxc.exchange.fix;

import com.fxc.exchange.book.MatchResult;
import com.fxc.exchange.book.NewOrder;
import com.fxc.exchange.book.Order;
import com.fxc.exchange.book.OrderBook;
import com.fxc.exchange.book.OrderStatus;
import com.fxc.exchange.book.OrderType;
import com.fxc.exchange.book.Side;
import com.fxc.exchange.book.Trade;
import com.fxc.exchange.service.MarketDataPublisher;
import com.fxc.exchange.service.MarketDataService;
import com.fxc.exchange.service.MatchingEngineService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import quickfix.Application;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MDEntryPx;
import quickfix.field.MDEntrySize;
import quickfix.field.MDEntryType;
import quickfix.field.MDReqID;
import quickfix.field.MDUpdateAction;
import quickfix.field.NoMDEntries;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.Text;

/**
 * The exchange's FIX 4.4 acceptor logic (docs/DESIGN.md §4.1). Inbound {@code NewOrderSingle(D)}
 * and {@code OrderCancelRequest(F)} drive the {@link MatchingEngineService}; outbound
 * {@code ExecutionReport(8)} reports acks/fills/rejects to both sides of each trade. Also serves as
 * the {@link MarketDataPublisher}, turning market-data callbacks into {@code W}/{@code X} messages.
 *
 * <p>A broker's identity is the FIX session's counterparty comp id ({@code sessionId.getTargetCompID()}).
 */
public final class ExchangeApplication extends MessageCracker implements Application, MarketDataPublisher {

    private final MatchingEngineService matchingService;
    private MarketDataService marketDataService;

    private final Map<String, SessionID> sessionsByBroker = new ConcurrentHashMap<>();
    private final AtomicLong execSeq = new AtomicLong();

    public ExchangeApplication(MatchingEngineService matchingService) {
        this.matchingService = matchingService;
    }

    public void setMarketDataService(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    // --- Application lifecycle ---

    @Override
    public void onCreate(SessionID sessionId) {
        register(sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        register(sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        // keep the mapping; session may reconnect
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, quickfix.IncorrectTagValue, quickfix.UnsupportedMessageType {
        register(sessionId);
        crack(message, sessionId);
    }

    // --- Business messages ---

    public void onMessage(quickfix.fix44.NewOrderSingle msg, SessionID sessionId) throws FieldNotFound {
        String clOrdId = msg.getString(ClOrdID.FIELD);
        String symbol = msg.getString(Symbol.FIELD);
        String broker = sessionId.getTargetCompID();
        Side side = msg.getChar(quickfix.field.Side.FIELD) == quickfix.field.Side.BUY ? Side.BUY : Side.SELL;
        OrderType type = msg.getChar(OrdType.FIELD) == OrdType.MARKET ? OrderType.MARKET : OrderType.LIMIT;
        BigDecimal qty = new BigDecimal(msg.getString(OrderQty.FIELD));
        BigDecimal price = msg.isSetField(Price.FIELD) ? new BigDecimal(msg.getString(Price.FIELD)) : null;

        MatchResult result = matchingService.submit(new NewOrder(clOrdId, broker, symbol, side, type, price, qty));
        Order order = result.order();

        if (!result.accepted()) {
            send(sessionId, order, ExecType.REJECTED, OrdStatus.REJECTED,
                    null, null, order.cumQty(), BigDecimal.ZERO, result.rejectReason());
            return;
        }

        // Acknowledge the new order.
        send(sessionId, order, ExecType.NEW, OrdStatus.NEW,
                null, null, BigDecimal.ZERO, order.quantity(), null);

        // Report each fill to both sides, with per-order running cumulative quantity.
        Map<String, BigDecimal> runningCum = new ConcurrentHashMap<>();
        for (Trade trade : result.trades()) {
            reportFill(sessionId, order, trade, runningCum);

            Side restingSide = side.opposite();
            Optional<Order> counter = matchingService.engine().order(trade.orderIdFor(restingSide));
            SessionID counterSession = sessionsByBroker.get(trade.brokerFor(restingSide));
            if (counter.isPresent() && counterSession != null) {
                reportFill(counterSession, counter.get(), trade, runningCum);
            }
        }

        // Market order remainder that could not fill was cancelled (IOC).
        if (order.status() == OrderStatus.CANCELLED) {
            send(sessionId, order, ExecType.CANCELED, OrdStatus.CANCELED,
                    null, null, order.cumQty(), BigDecimal.ZERO, "unfilled market remainder cancelled");
        }
    }

    public void onMessage(quickfix.fix44.OrderCancelRequest msg, SessionID sessionId) throws FieldNotFound {
        String origClOrdId = msg.getString(quickfix.field.OrigClOrdID.FIELD);
        Optional<Order> cancelled = matchingService.cancel(origClOrdId);
        if (cancelled.isPresent()) {
            Order order = cancelled.get();
            send(sessionId, order, ExecType.CANCELED, OrdStatus.CANCELED,
                    null, null, order.cumQty(), BigDecimal.ZERO, null);
        } else {
            sendCancelReject(sessionId, msg);
        }
    }

    public void onMessage(quickfix.fix44.MarketDataRequest msg, SessionID sessionId) throws FieldNotFound {
        String mdReqId = msg.getString(MDReqID.FIELD);
        int n = msg.getInt(quickfix.field.NoRelatedSym.FIELD);
        List<String> symbols = new ArrayList<>();
        quickfix.fix44.MarketDataRequest.NoRelatedSym group = new quickfix.fix44.MarketDataRequest.NoRelatedSym();
        for (int i = 1; i <= n; i++) {
            msg.getGroup(i, group);
            symbols.add(group.getString(Symbol.FIELD));
        }
        if (marketDataService != null) {
            marketDataService.subscribe(sessionId, mdReqId, symbols);
        }
    }

    // --- MarketDataPublisher ---

    @Override
    public void publishSnapshot(Object target, String mdReqId, String symbol,
                                OrderBook.Level bid, OrderBook.Level ask) {
        quickfix.fix44.MarketDataSnapshotFullRefresh snap = new quickfix.fix44.MarketDataSnapshotFullRefresh();
        snap.set(new MDReqID(mdReqId));
        snap.set(new Symbol(symbol));
        int entries = 0;
        if (bid != null) {
            snap.addGroup(snapshotEntry(MDEntryType.BID, bid));
            entries++;
        }
        if (ask != null) {
            snap.addGroup(snapshotEntry(MDEntryType.OFFER, ask));
            entries++;
        }
        if (entries == 0) {
            // Empty book: emit NoMDEntries=0 explicitly so the required group field is present
            // and the message validates against FIX44.
            snap.setInt(NoMDEntries.FIELD, 0);
        }
        sendTo((SessionID) target, snap);
    }

    @Override
    public void publishIncremental(Object target, String mdReqId, String symbol,
                                   OrderBook.Level bid, OrderBook.Level ask, List<Trade> trades) {
        quickfix.fix44.MarketDataIncrementalRefresh inc = new quickfix.fix44.MarketDataIncrementalRefresh();
        inc.set(new MDReqID(mdReqId));
        int entries = 0;
        if (bid != null) {
            inc.addGroup(incrementalEntry(MDEntryType.BID, symbol, bid.price(), bid.quantity()));
            entries++;
        }
        if (ask != null) {
            inc.addGroup(incrementalEntry(MDEntryType.OFFER, symbol, ask.price(), ask.quantity()));
            entries++;
        }
        for (Trade trade : trades) {
            inc.addGroup(incrementalEntry(MDEntryType.TRADE, symbol, trade.price(), trade.quantity()));
            entries++;
        }
        if (entries == 0) {
            return; // nothing to report; skip sending an empty incremental
        }
        sendTo((SessionID) target, inc);
    }

    // --- helpers ---

    private void register(SessionID sessionId) {
        sessionsByBroker.put(sessionId.getTargetCompID(), sessionId);
    }

    private void reportFill(SessionID sessionId, Order order, Trade trade, Map<String, BigDecimal> runningCum) {
        BigDecimal cum = runningCum.merge(order.orderId(), trade.quantity(), BigDecimal::add);
        BigDecimal leaves = order.quantity().subtract(cum);
        OrdStatus status = leaves.signum() == 0
                ? new OrdStatus(OrdStatus.FILLED)
                : new OrdStatus(OrdStatus.PARTIALLY_FILLED);
        send(sessionId, order, ExecType.TRADE, status.getValue(),
                trade.quantity(), trade.price(), cum, leaves, null);
    }

    private void send(SessionID sessionId, Order order, char execType, char ordStatus,
                      BigDecimal lastQty, BigDecimal lastPx, BigDecimal cumQty, BigDecimal leavesQty,
                      String text) {
        quickfix.fix44.ExecutionReport report = new quickfix.fix44.ExecutionReport(
                new OrderID("EX-" + order.sequence()),
                new ExecID("EXEC-" + execSeq.incrementAndGet()),
                new ExecType(execType),
                new OrdStatus(ordStatus),
                new quickfix.field.Side(order.side() == Side.BUY
                        ? quickfix.field.Side.BUY : quickfix.field.Side.SELL),
                new LeavesQty(leavesQty.doubleValue()),
                new CumQty(cumQty.doubleValue()),
                new AvgPx(lastPx != null ? lastPx.doubleValue() : 0.0));
        report.set(new ClOrdID(order.orderId()));
        report.set(new Symbol(order.symbol()));
        report.set(new OrderQty(order.quantity().doubleValue()));
        if (lastQty != null) {
            report.set(new LastQty(lastQty.doubleValue()));
        }
        if (lastPx != null) {
            report.set(new LastPx(lastPx.doubleValue()));
        }
        if (text != null) {
            report.set(new Text(text));
            if (execType == ExecType.REJECTED) {
                report.set(new OrdRejReason(OrdRejReason.OTHER));
            }
        }
        sendTo(sessionId, report);
    }

    private void sendCancelReject(SessionID sessionId, quickfix.fix44.OrderCancelRequest msg) throws FieldNotFound {
        quickfix.fix44.OrderCancelReject reject = new quickfix.fix44.OrderCancelReject(
                new OrderID("NONE"),
                new ClOrdID(msg.getString(ClOrdID.FIELD)),
                new quickfix.field.OrigClOrdID(msg.getString(quickfix.field.OrigClOrdID.FIELD)),
                new OrdStatus(OrdStatus.REJECTED),
                new quickfix.field.CxlRejResponseTo(quickfix.field.CxlRejResponseTo.ORDER_CANCEL_REQUEST));
        reject.set(new quickfix.field.CxlRejReason(quickfix.field.CxlRejReason.UNKNOWN_ORDER));
        sendTo(sessionId, reject);
    }

    private quickfix.fix44.MarketDataSnapshotFullRefresh.NoMDEntries snapshotEntry(
            char type, OrderBook.Level level) {
        quickfix.fix44.MarketDataSnapshotFullRefresh.NoMDEntries entry =
                new quickfix.fix44.MarketDataSnapshotFullRefresh.NoMDEntries();
        entry.set(new MDEntryType(type));
        entry.set(new MDEntryPx(level.price().doubleValue()));
        entry.set(new MDEntrySize(level.quantity().doubleValue()));
        return entry;
    }

    private quickfix.fix44.MarketDataIncrementalRefresh.NoMDEntries incrementalEntry(
            char type, String symbol, BigDecimal px, BigDecimal size) {
        quickfix.fix44.MarketDataIncrementalRefresh.NoMDEntries entry =
                new quickfix.fix44.MarketDataIncrementalRefresh.NoMDEntries();
        entry.set(new MDUpdateAction(MDUpdateAction.CHANGE));
        entry.set(new MDEntryType(type));
        entry.set(new Symbol(symbol));
        entry.set(new MDEntryPx(px.doubleValue()));
        entry.set(new MDEntrySize(size.doubleValue()));
        return entry;
    }

    private void sendTo(SessionID sessionId, Message message) {
        try {
            quickfix.Session.sendToTarget(message, sessionId);
        } catch (SessionNotFound e) {
            throw new IllegalStateException("FIX session not found: " + sessionId, e);
        }
    }
}
