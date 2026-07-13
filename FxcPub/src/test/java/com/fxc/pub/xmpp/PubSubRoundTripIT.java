package com.fxc.pub.xmpp;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jivesoftware.smackx.pubsub.SimplePayload;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;

/**
 * Proves the FxcPub XMPP-client architecture end-to-end: two Smack clients round-trip a status
 * through stock Tigase's XEP-0060 PubSub (the Phase-3 spike's exit). This is the concrete
 * validation that Tigase runs unmodified and FxcPub can publish/subscribe purely as a client.
 *
 * <p>Requires the Tigase container ({@code docker compose up tigase}); skips (does not fail the
 * build) when Tigase is not reachable on 127.0.0.1:5222.
 */
class PubSubRoundTripIT {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5222;
    private static final String DOMAIN = "fxc.local";
    private static final String STATUS_NS = "fxc:status";

    @Test
    void statusRoundTripsThroughTigasePubSub() throws Exception {
        assumeTrue(reachable(HOST, PORT), "Tigase not running on " + HOST + ":" + PORT + " — skipping");

        // Accounts are provisioned server-side by the tigase-init step (docker/tigase/init-schema.sh)
        // as trusted service accounts (docs/DESIGN.md §4.3). No in-band registration needed.
        XmppConnectionFactory factory = new XmppConnectionFactory(HOST, PORT, DOMAIN);
        XMPPTCPConnection publisher = factory.connect("broker", "secret");
        XMPPTCPConnection reader = factory.connect("investor", "secret");
        try {
            var service = JidCreate.bareFrom("pubsub." + DOMAIN);
            String nodeId = "broker-feed";

            PubSubManager pubMgr = PubSubManager.getInstanceFor(publisher, service);
            LeafNode pubNode = pubMgr.getOrCreateLeafNode(nodeId);

            PubSubManager readerMgr = PubSubManager.getInstanceFor(reader, service);
            LeafNode readerNode = readerMgr.getOrCreateLeafNode(nodeId);

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> payload = new AtomicReference<>();
            readerNode.addItemEventListener(event -> {
                for (Object item : event.getItems()) {
                    payload.set(String.valueOf(item));
                    received.countDown();
                }
            });
            readerNode.subscribe(reader.getUser().asEntityBareJidString());

            String status = "<status xmlns='" + STATUS_NS + "'>FILLED: BUY 100 ACME @ 42.10</status>";
            pubNode.publish(new PayloadItem<>("s-1", new SimplePayload("status", STATUS_NS, status)));

            assertTrue(received.await(10, TimeUnit.SECONDS),
                    "subscriber should receive the published status via Tigase PubSub");
            assertTrue(payload.get() != null && payload.get().contains("FILLED"),
                    "received payload should carry the rendered fill status, got: " + payload.get());
        } finally {
            publisher.disconnect();
            reader.disconnect();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
