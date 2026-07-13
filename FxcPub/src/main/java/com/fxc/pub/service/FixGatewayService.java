package com.fxc.pub.service;

import com.fxc.pub.xmpp.PubSubClient;
import java.math.BigDecimal;

/**
 * Renders broker {@code ExecutionReport} drop-copies into human-readable statuses and publishes
 * them to the broker's PubSub feed on Tigase, acting purely as an XMPP client (docs/DESIGN.md §4.3).
 * Also records the status into the GridGain timeline projection via {@link TimelineService}.
 *
 * <p>Feed node id for a broker is {@code feed-<brokerId>}.
 */
public final class FixGatewayService {

    public static final String STATUS_ELEMENT = "status";
    public static final String STATUS_NAMESPACE = "fxc:status";

    private final PubSubClient pubSubClient;
    private final TimelineService timeline;
    private final String author;

    public FixGatewayService(PubSubClient pubSubClient, TimelineService timeline, String author) {
        this.pubSubClient = pubSubClient;
        this.timeline = timeline;
        this.author = author;
    }

    public static String feedNode(String brokerId) {
        return "feed-" + brokerId;
    }

    /** Ensure a broker's feed node exists (owned by this publisher) so subscribers can attach. */
    public void ensureFeed(String brokerId) throws Exception {
        pubSubClient.openFeed(feedNode(brokerId));
    }

    /** Render a fill and publish it to the broker's feed. Returns the rendered status body. */
    public String publishFill(String brokerId, String symbol, String side,
                              BigDecimal lastQty, BigDecimal lastPx) throws Exception {
        String body = render(side, lastQty, symbol, lastPx);
        String feed = brokerId;
        StatusRecord status = timeline.record(feed, author, body, System.currentTimeMillis());
        String xml = "<" + STATUS_ELEMENT + " xmlns='" + STATUS_NAMESPACE + "'>" + escape(body)
                + "</" + STATUS_ELEMENT + ">";
        pubSubClient.publish(feedNode(brokerId), status.statusId(), STATUS_ELEMENT, STATUS_NAMESPACE, xml);
        return body;
    }

    private static String render(String side, BigDecimal qty, String symbol, BigDecimal px) {
        return "FILLED: " + side + " " + qty.stripTrailingZeros().toPlainString()
                + " " + symbol + " @ " + px.stripTrailingZeros().toPlainString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
