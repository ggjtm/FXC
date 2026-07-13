package com.fxc.broker.oms;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import quickfix.SessionSettings;

/** Builds QuickFIX/J initiator settings programmatically (broker → exchange). */
public final class FixSettingsFactory {

    private FixSettingsFactory() {
    }

    public static SessionSettings initiator(String host, int port, String senderCompId, String targetCompId) {
        String cfg = """
                [DEFAULT]
                ConnectionType=initiator
                BeginString=FIX.4.4
                UseDataDictionary=Y
                DataDictionary=FIX44.xml
                StartTime=00:00:00
                EndTime=00:00:00
                HeartBtInt=10
                ReconnectInterval=1
                SocketConnectHost=%s
                SocketConnectPort=%d

                [SESSION]
                SenderCompID=%s
                TargetCompID=%s
                """.formatted(host, port, senderCompId, targetCompId);
        try {
            return new SessionSettings(new ByteArrayInputStream(cfg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build FIX initiator settings", e);
        }
    }
}
