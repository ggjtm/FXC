package com.fxc.broker.oms;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.Initiator;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.SLF4JLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.Side;
import quickfix.field.Symbol;

/**
 * QuickFIX/J drop-copy initiator to FxcPub (docs/DESIGN.md §4.2). On each fill the broker sends a
 * copy of the {@code ExecutionReport} to FxcPub's drop-copy acceptor, where it is rendered as a
 * status and published to the broker's feed.
 */
public final class BrokerDropCopyClient implements Application, DropCopyPublisher, AutoCloseable {

    private final Initiator initiator;
    private final CountDownLatch logon = new CountDownLatch(1);
    private final AtomicLong execSeq = new AtomicLong();
    private volatile SessionID pubSession;

    public BrokerDropCopyClient(SessionSettings settings) throws Exception {
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

    @Override
    public void publishFill(String clOrdId, String symbol, String side, BigDecimal lastQty, BigDecimal lastPx) {
        SessionID session = pubSession;
        if (session == null) {
            return; // not connected to FxcPub; drop-copy is best-effort
        }
        quickfix.fix44.ExecutionReport report = new quickfix.fix44.ExecutionReport(
                new OrderID("DC-" + execSeq.incrementAndGet()),
                new ExecID("DC-" + execSeq.get()),
                new ExecType(ExecType.TRADE),
                new OrdStatus(OrdStatus.FILLED),
                new Side("SELL".equals(side) ? Side.SELL : Side.BUY),
                new LeavesQty(0),
                new CumQty(lastQty.doubleValue()),
                new AvgPx(lastPx.doubleValue()));
        report.set(new ClOrdID(clOrdId));
        report.set(new Symbol(symbol));
        report.set(new LastQty(lastQty.doubleValue()));
        report.set(new LastPx(lastPx.doubleValue()));
        try {
            Session.sendToTarget(report, session);
        } catch (SessionNotFound e) {
            // FxcPub session went away; drop.
        }
    }

    @Override public void onCreate(SessionID sessionId) { this.pubSession = sessionId; }
    @Override public void onLogon(SessionID sessionId) { this.pubSession = sessionId; logon.countDown(); }
    @Override public void onLogout(SessionID sessionId) { }
    @Override public void toAdmin(Message message, SessionID sessionId) { }
    @Override public void fromAdmin(Message message, SessionID sessionId) { }
    @Override public void toApp(Message message, SessionID sessionId) { }
    @Override public void fromApp(Message message, SessionID sessionId) { }
}
