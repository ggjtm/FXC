package com.fxc.investor.feed;

import com.fxc.investor.strategy.MarketView;
import java.math.BigDecimal;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.X509TrustManager;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jivesoftware.smackx.pubsub.SimplePayload;
import org.jxmpp.jid.impl.JidCreate;

/**
 * FxcInvestor's XMPP feed client (docs/DESIGN.md §4.4, PLAN item 3): connects to stock Tigase as a
 * trusted Smack client, subscribes to a broker's PubSub feed, and folds each fill status into the
 * agent's {@link MarketView} — updating the last sale (all agents) and the traded-volume histogram
 * (feeds {@code bookfish}). Also posts the agent's own commentary.
 *
 * <p>Feed node for a broker is {@code feed-<brokerId>} (matches FxcPub's FixGatewayService); fill
 * statuses are rendered as {@code FILLED: <side> <qty> <symbol> @ <price>}.
 */
public final class FeedClient implements AutoCloseable {

    public static final String STATUS_ELEMENT = "status";
    public static final String STATUS_NAMESPACE = "fxc:status";

    private static final Pattern FILL = Pattern.compile(
            "FILLED:\\s*(BUY|SELL)\\s+([0-9]+(?:\\.[0-9]+)?)\\s+(\\S+)\\s+@\\s+([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern STATUS_BODY = Pattern.compile("<status[^>]*>(.*?)</status>", Pattern.DOTALL);
    private static final int RECENT_LIMIT = 50;

    private final String host;
    private final int port;
    private final String domain;
    private final Deque<String> recent = new ArrayDeque<>();
    private XMPPTCPConnection connection;
    private PubSubManager pubSub;

    public FeedClient(String host, int port, String domain) {
        this.host = host;
        this.port = port;
        this.domain = domain;
    }

    public void connect(String user, String password) throws Exception {
        connection = new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(JidCreate.domainBareFrom(domain))
                .setHost(host)
                .setPort(port)
                .setSecurityMode(SecurityMode.required)
                .setCustomX509TrustManager(TRUST_ALL) // dev: trust Tigase's self-signed cert
                .setHostnameVerifier((h, s) -> true)
                .setUsernameAndPassword(user, password)
                .build());
        connection.connect();
        connection.login();
        pubSub = PubSubManager.getInstanceFor(connection, JidCreate.bareFrom("pubsub." + domain));
    }

    /** Ensure a broker's feed node exists (so a subscriber can attach before the first publish). */
    public LeafNode ensureFeed(String brokerId) throws Exception {
        return pubSub.getOrCreateLeafNode(feedNode(brokerId));
    }

    /** Subscribe to a broker's feed; parsed fills update {@code market}. */
    public void subscribeFeed(String brokerId, MarketView market) throws Exception {
        LeafNode node = pubSub.getOrCreateLeafNode(feedNode(brokerId));
        node.addItemEventListener(event -> {
            for (Object item : event.getItems()) {
                record(String.valueOf(item), market);
            }
        });
        node.subscribe(connection.getUser().asEntityBareJidString());
    }

    /** Buffer a received status (for the CLI {@code feed} command) and fold any fill into market. */
    private void record(String itemXml, MarketView market) {
        Matcher body = STATUS_BODY.matcher(itemXml);
        String text = body.find() ? body.group(1) : itemXml;
        synchronized (recent) {
            recent.addLast(text);
            while (recent.size() > RECENT_LIMIT) {
                recent.removeFirst();
            }
        }
        ingest(itemXml, market);
    }

    /** The most recent {@code n} feed statuses, oldest first. */
    public List<String> recentStatuses(int n) {
        synchronized (recent) {
            List<String> all = new ArrayList<>(recent);
            int from = Math.max(0, all.size() - n);
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    /** Post a status to a feed (the agent's own commentary). */
    public void publishStatus(String brokerId, String text) throws Exception {
        LeafNode node = pubSub.getOrCreateLeafNode(feedNode(brokerId));
        String xml = "<" + STATUS_ELEMENT + " xmlns='" + STATUS_NAMESPACE + "'>" + escape(text)
                + "</" + STATUS_ELEMENT + ">";
        node.publish(new PayloadItem<>(null, new SimplePayload(STATUS_ELEMENT, STATUS_NAMESPACE, xml)));
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** Parse a fill status and fold it into the market view. Non-fill items are ignored. */
    static void ingest(String itemXml, MarketView market) {
        Matcher m = FILL.matcher(itemXml);
        if (m.find()) {
            String symbol = m.group(3);
            BigDecimal quantity = new BigDecimal(m.group(2));
            BigDecimal price = new BigDecimal(m.group(4));
            market.recordTrade(symbol, price, quantity);
        }
    }

    public static String feedNode(String brokerId) {
        return "feed-" + brokerId;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
