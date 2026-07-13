package com.fxc.pub.fix;

import com.fxc.pub.service.FixGatewayService;
import java.math.BigDecimal;
import quickfix.Application;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.MessageCracker;
import quickfix.SessionID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.Side;
import quickfix.field.Symbol;

/**
 * FxcPub's FIX 4.4 drop-copy acceptor logic (docs/DESIGN.md §4.3): receives {@code ExecutionReport}
 * drop-copies from brokers and hands fills to {@link FixGatewayService}, which renders them as
 * statuses and publishes them to the broker's PubSub feed. The broker id is the FIX session's
 * counterparty comp id.
 */
public final class PubFixApplication extends MessageCracker implements Application {

    private final FixGatewayService gateway;

    public PubFixApplication(FixGatewayService gateway) {
        this.gateway = gateway;
    }

    public void onMessage(quickfix.fix44.ExecutionReport report, SessionID sessionId) throws FieldNotFound {
        if (report.getChar(ExecType.FIELD) != ExecType.TRADE) {
            return; // only fills become statuses
        }
        String broker = sessionId.getTargetCompID();
        String symbol = report.getString(Symbol.FIELD);
        String side = report.getChar(Side.FIELD) == Side.BUY ? "BUY" : "SELL";
        BigDecimal lastQty = report.isSetField(LastQty.FIELD) ? new BigDecimal(report.getString(LastQty.FIELD)) : BigDecimal.ZERO;
        BigDecimal lastPx = report.isSetField(LastPx.FIELD) ? new BigDecimal(report.getString(LastPx.FIELD)) : BigDecimal.ZERO;
        try {
            gateway.publishFill(broker, symbol, side, lastQty, lastPx);
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish fill status for " + broker, e);
        }
    }

    @Override public void onCreate(SessionID sessionId) { }

    @Override
    public void onLogon(SessionID sessionId) {
        // Pre-create the broker's feed node (owned by FxcPub's publisher) so subscribers can attach
        // before the first fill is published.
        try {
            gateway.ensureFeed(sessionId.getTargetCompID());
        } catch (Exception e) {
            // best-effort; the node will also be created lazily on first publish
        }
    }

    @Override public void onLogout(SessionID sessionId) { }
    @Override public void toAdmin(Message message, SessionID sessionId) { }
    @Override public void fromAdmin(Message message, SessionID sessionId) { }
    @Override public void toApp(Message message, SessionID sessionId) { }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, quickfix.IncorrectTagValue, quickfix.UnsupportedMessageType {
        crack(message, sessionId);
    }
}
