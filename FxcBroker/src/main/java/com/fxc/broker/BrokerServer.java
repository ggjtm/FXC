package com.fxc.broker;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.archive.ArchiveService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.grid.BrokerTables;
import com.fxc.broker.grid.GridNode;
import com.fxc.broker.ofx.OfxHttpServer;
import com.fxc.broker.ofx.OfxService;
import com.fxc.broker.oms.BrokerDropCopyClient;
import com.fxc.broker.oms.BrokerFixClient;
import com.fxc.broker.oms.OmsService;
import com.fxc.common.store.ColdStore;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import quickfix.SessionSettings;

/**
 * Assembles and runs a complete FxcBroker: an embedded GridGain node with the broker tables, the
 * account/OMS services, a QuickFIX/J initiator to FxcExchange, and the OFX HTTP server. One object
 * owns the whole component lifecycle.
 */
public final class BrokerServer implements AutoCloseable {

    private final GridNode node;
    private final AccountService accountService;
    private final OmsService omsService;
    private final BrokerFixClient fixClient;
    private final BrokerDropCopyClient dropCopyClient;
    private final OfxHttpServer ofxServer;
    private final ArchiveService archiveService;
    private final ScheduledExecutorService archiveExecutor;
    private final ColdStore coldStore;

    private BrokerServer(GridNode node, AccountService accountService, OmsService omsService,
                         BrokerFixClient fixClient, BrokerDropCopyClient dropCopyClient, OfxHttpServer ofxServer,
                         ArchiveService archiveService, ScheduledExecutorService archiveExecutor,
                         ColdStore coldStore) {
        this.node = node;
        this.accountService = accountService;
        this.omsService = omsService;
        this.fixClient = fixClient;
        this.dropCopyClient = dropCopyClient;
        this.ofxServer = ofxServer;
        this.archiveService = archiveService;
        this.archiveExecutor = archiveExecutor;
        this.coldStore = coldStore;
    }

    /**
     * @param dropCopySettings FIX initiator settings for the drop-copy session to FxcPub, or
     *                         {@code null} to run without publishing fills to FxcPub.
     */
    public static BrokerServer start(String gridInstanceName, int gridDiscoveryPort, String workDir,
                                     SessionSettings fixInitiatorSettings,
                                     String ofxHost, int ofxPort, String ofxUser, String ofxPassword,
                                     String brokerId, Consumer<AccountService> seeder,
                                     SessionSettings dropCopySettings) throws Exception {
        return start(gridInstanceName, gridDiscoveryPort, workDir, fixInitiatorSettings, ofxHost, ofxPort,
                ofxUser, ofxPassword, brokerId, seeder, dropCopySettings, null, 0);
    }

    /**
     * Full variant that also wires cold-data archival (root PLAN Phase 5): terminal client orders and
     * their executions drain from GridGain to MariaDB on a fixed schedule.
     *
     * @param coldStore          the MariaDB cold store, or {@code null} to run without archival.
     * @param archiveIntervalMs  archive period in ms; {@code <= 0} disables the scheduler (manual
     *                           {@link #archiveService()} passes only — used by tests).
     */
    public static BrokerServer start(String gridInstanceName, int gridDiscoveryPort, String workDir,
                                     SessionSettings fixInitiatorSettings,
                                     String ofxHost, int ofxPort, String ofxUser, String ofxPassword,
                                     String brokerId, Consumer<AccountService> seeder,
                                     SessionSettings dropCopySettings,
                                     ColdStore coldStore, long archiveIntervalMs) throws Exception {
        GridNode node = GridNode.start(gridInstanceName, gridDiscoveryPort, workDir);
        try {
            BrokerTables.createAll(node.ignite());
            BrokerRepository repository = new BrokerRepository(node.ignite());
            AccountService accountService = new AccountService(repository);
            seeder.accept(accountService);

            OmsService omsService = new OmsService(accountService, repository);
            BrokerFixClient fixClient = new BrokerFixClient(fixInitiatorSettings, omsService);
            omsService.setRouter(fixClient);

            // Market-data relay: cache exchange book snapshots for the OFX book handler
            // (FxcBroker/docs/stories/001).
            com.fxc.broker.md.MarketDataCache marketData = new com.fxc.broker.md.MarketDataCache();
            fixClient.setMarketDataCache(marketData);

            fixClient.start();
            // Best-effort: wait for the exchange session so routing/market-data work immediately.
            if (fixClient.awaitLogon(15, TimeUnit.SECONDS)) {
                fixClient.subscribeMarketData(
                        List.copyOf(com.fxc.common.instrument.InstrumentCatalog.bySymbol().keySet()));
            }

            BrokerDropCopyClient dropCopyClient = null;
            if (dropCopySettings != null) {
                dropCopyClient = new BrokerDropCopyClient(dropCopySettings);
                dropCopyClient.start();
                dropCopyClient.awaitLogon(15, TimeUnit.SECONDS);
                fixClient.setDropCopyPublisher(dropCopyClient);
            }

            OfxService ofxService = new OfxService(omsService, accountService, marketData, ofxUser, ofxPassword, brokerId);
            OfxHttpServer ofxServer = new OfxHttpServer(ofxHost, ofxPort, "/ofx", ofxService);
            ofxServer.start();

            ArchiveService archiveService = null;
            ScheduledExecutorService archiveExecutor = null;
            if (coldStore != null) {
                archiveService = new ArchiveService(node.ignite(), coldStore, System::currentTimeMillis);
                if (archiveIntervalMs > 0) {
                    ArchiveService svc = archiveService;
                    archiveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "fxc-broker-archive");
                        t.setDaemon(true);
                        return t;
                    });
                    archiveExecutor.scheduleWithFixedDelay(() -> {
                        try {
                            svc.archiveNow();
                        } catch (RuntimeException ex) {
                            System.err.println("Broker archival pass failed: " + ex.getMessage());
                        }
                    }, archiveIntervalMs, archiveIntervalMs, TimeUnit.MILLISECONDS);
                }
            }

            return new BrokerServer(node, accountService, omsService, fixClient, dropCopyClient, ofxServer,
                    archiveService, archiveExecutor, coldStore);
        } catch (Exception e) {
            node.close();
            throw e;
        }
    }

    /** The cold-data archival service, or {@code null} if archival is not configured. */
    public ArchiveService archiveService() {
        return archiveService;
    }

    public AccountService accountService() {
        return accountService;
    }

    public OmsService omsService() {
        return omsService;
    }

    public BrokerFixClient fixClient() {
        return fixClient;
    }

    public int ofxPort() {
        return ofxServer.boundPort();
    }

    @Override
    public void close() {
        if (archiveExecutor != null) {
            archiveExecutor.shutdownNow();
        }
        ofxServer.close();
        if (dropCopyClient != null) {
            dropCopyClient.close();
        }
        fixClient.close();
        node.close();
        if (coldStore != null) {
            coldStore.close();
        }
    }
}
