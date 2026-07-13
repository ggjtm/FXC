package com.fxc.pub.xmpp;

import java.util.function.Consumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.pubsub.LeafNode;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jivesoftware.smackx.pubsub.SimplePayload;
import org.jxmpp.jid.impl.JidCreate;

/**
 * Thin XEP-0060 PubSub client over a Smack connection (docs/DESIGN.md §4.3). FxcPub's services use
 * this to publish statuses to and subscribe to feed nodes on stock Tigase — purely as an XMPP
 * client. Feed nodes are leaf nodes on Tigase's pubsub service ({@code pubsub.<domain>}).
 */
public final class PubSubClient {

    private final PubSubManager manager;
    private final String subscriberJid;

    public PubSubClient(XMPPConnection connection, String domain) throws Exception {
        this.manager = PubSubManager.getInstanceFor(connection, JidCreate.bareFrom("pubsub." + domain));
        this.subscriberJid = connection.getUser().asEntityBareJidString();
    }

    /** Get or create the leaf node for a feed. */
    public LeafNode openFeed(String nodeId) throws Exception {
        return manager.getOrCreateLeafNode(nodeId);
    }

    /** Publish a status payload to a feed node. */
    public void publish(String nodeId, String itemId, String elementName, String namespace, String xmlBody)
            throws Exception {
        LeafNode node = openFeed(nodeId);
        node.publish(new PayloadItem<>(itemId, new SimplePayload(elementName, namespace, xmlBody)));
    }

    /** Subscribe to a feed node; {@code onItem} receives each published item's XML. */
    public void subscribe(String nodeId, Consumer<String> onItem) throws Exception {
        LeafNode node = openFeed(nodeId);
        node.addItemEventListener(event -> {
            for (Object item : event.getItems()) {
                onItem.accept(String.valueOf(item));
            }
        });
        node.subscribe(subscriberJid);
    }
}
