package com.fxc.pub;

import com.fxc.common.store.ColdStore;
import com.fxc.pub.archive.ArchiveService;
import com.fxc.pub.fix.PubFixApplication;
import com.fxc.pub.grid.GridNode;
import com.fxc.pub.grid.PubRepository;
import com.fxc.pub.grid.PubTables;
import com.fxc.pub.service.FixGatewayService;
import com.fxc.pub.service.TimelineService;
import com.fxc.pub.xmpp.PubSubClient;
import com.fxc.pub.xmpp.XmppConnectionFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ArchiveService archiveService;
    private final ScheduledExecutorService archiveExecutor;
    private final ColdStore coldStore;

    private PubServer(GridNode node, XMPPTCPConnection xmpp, TimelineService timelineService,
                      FixGatewayService fixGatewayService, Acceptor dropCopyAcceptor,
                      ArchiveService archiveService, ScheduledExecutorService archiveExecutor,
                      ColdStore coldStore) {
        this.node = node;
        this.xmpp = xmpp;
        this.timelineService = timelineService;
        this.fixGatewayService = fixGatewayService;
        this.dropCopyAcceptor = dropCopyAcceptor;
        this.archiveService = archiveService;
        this.archiveExecutor = archiveExecutor;
        this.coldStore = coldStore;
    }

    public static PubServer start(String gridInstance, int gridDiscoveryPort, String workDir,
                                  String xmppHost, int xmppPort, String xmppDomain,
                                  String xmppUser, String xmppPassword,
                                  SessionSettings dropCopySettings) throws Exception {
        return start(gridInstance, gridDiscoveryPort, workDir, xmppHost, xmppPort, xmppDomain,
                xmppUser, xmppPassword, dropCopySettings, null, 0, 0);
    }

    /**
     * Full variant that also wires cold-data archival (root PLAN Phase 5): statuses older than
     * {@code retentionMs} drain from the GridGain hot projection to MariaDB on a fixed schedule, and
     * deep-history timeline reads fall back to the cold archive.
     *
     * @param coldStore          the MariaDB cold store, or {@code null} to run without archival.
     * @param archiveIntervalMs  archive period in ms; {@code <= 0} disables the scheduler (manual
     *                           {@link #archiveService()} passes only — used by tests).
     * @param retentionMs        keep statuses newer than this hot; older ones are archived.
     */
    public static PubServer start(String gridInstance, int gridDiscoveryPort, String workDir,
                                  String xmppHost, int xmppPort, String xmppDomain,
                                  String xmppUser, String xmppPassword,
                                  SessionSettings dropCopySettings,
                                  ColdStore coldStore, long archiveIntervalMs, long retentionMs) throws Exception {
        GridNode node = GridNode.start(gridInstance, gridDiscoveryPort, workDir);
        XMPPTCPConnection xmpp = null;
        try {
            PubTables.createAll(node.ignite());
            PubRepository repository = new PubRepository(node.ignite());
            TimelineService timelineService = new TimelineService(repository, coldStore);

            xmpp = new XmppConnectionFactory(xmppHost, xmppPort, xmppDomain).connect(xmppUser, xmppPassword);
            PubSubClient pubSubClient = new PubSubClient(xmpp, xmppDomain);
            FixGatewayService fixGatewayService =
                    new FixGatewayService(pubSubClient, timelineService, xmppUser + "@" + xmppDomain);

            PubFixApplication application = new PubFixApplication(fixGatewayService);
            Acceptor acceptor = new SocketAcceptor(application, new MemoryStoreFactory(), dropCopySettings,
                    new SLF4JLogFactory(dropCopySettings), new DefaultMessageFactory());
            acceptor.start();

            ArchiveService archiveService = null;
            ScheduledExecutorService archiveExecutor = null;
            if (coldStore != null) {
                archiveService = new ArchiveService(node.ignite(), coldStore, System::currentTimeMillis, retentionMs);
                if (archiveIntervalMs > 0) {
                    ArchiveService svc = archiveService;
                    archiveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "fxc-pub-archive");
                        t.setDaemon(true);
                        return t;
                    });
                    archiveExecutor.scheduleWithFixedDelay(() -> {
                        try {
                            svc.archiveNow();
                        } catch (RuntimeException ex) {
                            System.err.println("Pub archival pass failed: " + ex.getMessage());
                        }
                    }, archiveIntervalMs, archiveIntervalMs, TimeUnit.MILLISECONDS);
                }
            }

            return new PubServer(node, xmpp, timelineService, fixGatewayService, acceptor,
                    archiveService, archiveExecutor, coldStore);
        } catch (Exception e) {
            if (xmpp != null) {
                xmpp.disconnect();
            }
            node.close();
            throw e;
        }
    }

    /** The cold-data archival service, or {@code null} if archival is not configured. */
    public ArchiveService archiveService() {
        return archiveService;
    }

    public TimelineService timelineService() {
        return timelineService;
    }

    public FixGatewayService fixGatewayService() {
        return fixGatewayService;
    }

    @Override
    public void close() {
        if (archiveExecutor != null) {
            archiveExecutor.shutdownNow();
        }
        dropCopyAcceptor.stop();
        xmpp.disconnect();
        node.close();
        if (coldStore != null) {
            coldStore.close();
        }
    }
}
