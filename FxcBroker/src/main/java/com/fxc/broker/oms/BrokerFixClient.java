package com.fxc.broker.oms;

import com.fxc.broker.model.ClientOrder;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Side;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.Initiator;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;

/**
 * QuickFIX/J initiator to FxcExchange (docs/DESIGN.md §4.2). Sends {@code NewOrderSingle} for routed
 * client orders and feeds inbound {@code ExecutionReport}s back to {@link OmsService}. Implements
 * {@link OrderRouter} so the OMS depends only on the routing seam, not on FIX types.
 */
public final class BrokerFixClient extends MessageCracker implements Application, OrderRouter, AutoCloseable {

    private final OmsService oms;
    private final Initiator initiator;
    private final CountDownLatch logon = new CountDownLatch(1);
    private volatile SessionID exchangeSession;
    private volatile DropCopyPublisher dropCopy = DropCopyPublisher.NONE;

    /** Set the drop-copy publisher that forwards fills to FxcPub (default: no-op). */
    public void setDropCopyPublisher(DropCopyPublisher dropCopy) {
        this.dropCopy = dropCopy;
    }

    public BrokerFixClient(SessionSettings settings, OmsService oms) throws Exception {
        this.oms = oms;
        this.initiator = new SocketInitiator(this, new MemoryStoreFactory(), settings,
                new SLF4JLogFactory(settings), new DefaultMessageFactory());
    }

    public void start() throws Exception {
        initiator.start();
    }

    public boolean awaitLogon(long timeout, TimeUnit unit) throws InterruptedException {
        return logon.await(timeout, unit);
    }

    @Override
    public void close() {
        initiator.stop();
    }

    // --- OrderRouter ---

    @Override
    public void route(ClientOrder order) {
        if (exchangeSession == null) {
            throw new IllegalStateException("not logged on to the exchange yet");
        }
        quickfix.fix44.NewOrderSingle msg = new quickfix.fix44.NewOrderSingle(
                new ClOrdID(order.clientOrderId()),
                new quickfix.field.Side(order.side() == Side.BUY
                        ? quickfix.field.Side.BUY : quickfix.field.Side.SELL),
                new TransactTime(),
                new OrdType(order.type() == OrderType.MARKET ? OrdType.MARKET : OrdType.LIMIT));
        msg.set(new Symbol(order.symbol()));
        msg.set(new OrderQty(order.quantity().doubleValue()));
        if (order.type() == OrderType.LIMIT && order.limitPrice() != null) {
            msg.set(new Price(order.limitPrice().doubleValue()));
        }
        try {
            Session.sendToTarget(msg, exchangeSession);
        } catch (SessionNotFound e) {
            throw new IllegalStateException("exchange session not found: " + exchangeSession, e);
        }
    }

    // --- Application ---

    @Override
    public void onCreate(SessionID sessionId) {
        this.exchangeSession = sessionId;
    }

    @Override
    public void onLogon(SessionID sessionId) {
        this.exchangeSession = sessionId;
        logon.countDown();
    }

    @Override
    public void onLogout(SessionID sessionId) {
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
        crack(message, sessionId);
    }

    public void onMessage(quickfix.fix44.ExecutionReport report, SessionID sessionId) throws FieldNotFound {
        String clOrdId = report.getString(ClOrdID.FIELD);
        String execId = report.getString(ExecID.FIELD);
        String exchangeOrderId = report.isSetField(OrderID.FIELD) ? report.getString(OrderID.FIELD) : null;
        char execType = report.getChar(ExecType.FIELD);
        char ordStatus = report.getChar(OrdStatus.FIELD);
        BigDecimal lastQty = decimal(report, LastQty.FIELD);
        BigDecimal lastPx = decimal(report, LastPx.FIELD);
        BigDecimal cumQty = report.isSetField(CumQty.FIELD) ? decimal(report, CumQty.FIELD) : BigDecimal.ZERO;
        String text = report.isSetField(Text.FIELD) ? report.getString(Text.FIELD) : null;

        boolean isFill = execType == ExecType.TRADE;
        boolean isReject = execType == ExecType.REJECTED || ordStatus == OrdStatus.REJECTED;

        oms.onExecutionReport(clOrdId, execId, exchangeOrderId, isFill, isReject, lastQty, lastPx, cumQty, text);

        if (isFill && lastQty != null) {
            // Drop-copy the fill to FxcPub (best-effort).
            String symbol = report.getString(Symbol.FIELD);
            String side = report.getChar(quickfix.field.Side.FIELD) == quickfix.field.Side.BUY ? "BUY" : "SELL";
            dropCopy.publishFill(clOrdId, symbol, side, lastQty, lastPx);
        }
    }

    /**
     * Read a FIX decimal field, normalized to scale 8. FIX transports prices/quantities as
     * doubles, which stringify with long fractional scales; the GridGain columns are DECIMAL(_,8),
     * so we clamp here at the boundary.
     */
    private static BigDecimal decimal(Message message, int field) throws FieldNotFound {
        if (!message.isSetField(field)) {
            return null;
        }
        return new BigDecimal(message.getString(field)).setScale(8, java.math.RoundingMode.HALF_UP);
    }
}

