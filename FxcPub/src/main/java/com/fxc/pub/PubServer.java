package com.fxc.pub;

import com.fxc.pub.fix.PubFixApplication;
import com.fxc.pub.grid.GridNode;
import com.fxc.pub.grid.PubRepository;
import com.fxc.pub.grid.PubTables;
import com.fxc.pub.service.FixGatewayService;
import com.fxc.pub.service.TimelineService;
import com.fxc.pub.xmpp.PubSubClient;
import com.fxc.pub.xmpp.XmppConnectionFactory;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import quickfix.Acceptor;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/**
 * Assembles and runs FxcPub's application layer (docs/DESIGN.md §4.3): an embedded GridGain node
 * with the projection tables, a trusted Smack client to stock Tigase, the timeline + FIX-gateway
 * services, and a FIX drop-copy acceptor. Tigase itself runs as a separate (unmodified) service —
 * it is NOT started here.
 */
public final class PubServer implements AutoCloseable {

    private final GridNode node;
    private final XMPPTCPConnection xmpp;
    private final TimelineService timelineService;
    private final FixGatewayService fixGatewayService;
    private final Acceptor dropCopyAcceptor;

    private PubServer(GridNode node, XMPPTCPConnection xmpp, TimelineService timelineService,
                      FixGatewayService fixGatewayService, Acceptor dropCopyAcceptor) {
        this.node = node;
        this.xmpp = xmpp;
        this.timelineService = timelineService;
        this.fixGatewayService = fixGatewayService;
        this.dropCopyAcceptor = dropCopyAcceptor;
    }

    public static PubServer start(String gridInstance, int gridDiscoveryPort, String workDir,
                                  String xmppHost, int xmppPort, String xmppDomain,
                                  String xmppUser, String xmppPassword,
                                  SessionSettings dropCopySettings) throws Exception {
        GridNode node = GridNode.start(gridInstance, gridDiscoveryPort, workDir);
        XMPPTCPConnection xmpp = null;
        try {
            PubTables.createAll(node.ignite());
            PubRepository repository = new PubRepository(node.ignite());
            TimelineService timelineService = new TimelineService(repository);

            xmpp = new XmppConnectionFactory(xmppHost, xmppPort, xmppDomain).connect(xmppUser, xmppPassword);
            PubSubClient pubSubClient = new PubSubClient(xmpp, xmppDomain);
            FixGatewayService fixGatewayService =
                    new FixGatewayService(pubSubClient, timelineService, xmppUser + "@" + xmppDomain);

            PubFixApplication application = new PubFixApplication(fixGatewayService);
            Acceptor acceptor = new SocketAcceptor(application, new MemoryStoreFactory(), dropCopySettings,
                    new SLF4JLogFactory(dropCopySettings), new DefaultMessageFactory());
            acceptor.start();

            return new PubServer(node, xmpp, timelineService, fixGatewayService, acceptor);
        } catch (Exception e) {
            if (xmpp != null) {
                xmpp.disconnect();
            }
            node.close();
            throw e;
        }
    }

    public TimelineService timelineService() {
        return timelineService;
    }

    public FixGatewayService fixGatewayService() {
        return fixGatewayService;
    }

    @Override
    public void close() {
        dropCopyAcceptor.stop();
        xmpp.disconnect();
        node.close();
    }
}
