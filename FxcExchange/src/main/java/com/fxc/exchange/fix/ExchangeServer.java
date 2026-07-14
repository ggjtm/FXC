package com.fxc.exchange.fix;

import com.fxc.common.instrument.Instrument;
import com.fxc.common.store.ColdStore;
import com.fxc.exchange.archive.ArchiveService;
import com.fxc.exchange.book.MatchingEngine;
import com.fxc.exchange.feed.FeedService;
import com.fxc.exchange.grid.ExchangeRepository;
import com.fxc.exchange.grid.ExchangeTables;
import com.fxc.exchange.grid.GridNode;
import com.fxc.exchange.service.ClearingService;
import com.fxc.exchange.service.MarketDataService;
import com.fxc.exchange.service.MatchingEngineService;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import quickfix.Acceptor;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/**
 * Assembles and runs a complete FxcExchange: an embedded GridGain node with the exchange tables,
 * the matching/market-data/clearing services, a QuickFIX/J acceptor, and (optionally) the cold-data
 * {@link ArchiveService}. One object owns the whole component lifecycle (start via {@link #start},
 * stop via {@link #close}).
 */
public final class ExchangeServer implements AutoCloseable {

    private final GridNode node;
    private final MatchingEngineService matchingService;
    private final ClearingService clearingService;
    private final Acceptor acceptor;
    private final ArchiveService archiveService;         // nullable
    private final ScheduledExecutorService archiveScheduler; // nullable
    private final ColdStore coldStore;                   // nullable
    private final FeedService feedService;               // nullable

    private ExchangeServer(GridNode node, MatchingEngineService matchingService,
                           ClearingService clearingService, Acceptor acceptor,
                           ArchiveService archiveService, ScheduledExecutorService archiveScheduler,
                           ColdStore coldStore, FeedService feedService) {
        this.node = node;
        this.matchingService = matchingService;
        this.clearingService = clearingService;
        this.acceptor = acceptor;
        this.archiveService = archiveService;
        this.archiveScheduler = archiveScheduler;
        this.coldStore = coldStore;
        this.feedService = feedService;
    }

    /** Start without cold-data archival. */
    public static ExchangeServer start(SessionSettings settings, String instanceName, int discoveryPort,
                                       String workDirectory, List<Instrument> instruments) throws Exception {
        return start(settings, instanceName, discoveryPort, workDirectory, instruments, null, 0);
    }

    /**
     * Start, optionally with cold-data archival.
     *
     * @param coldStore         the MariaDB cold store, or {@code null} to run without archival
     * @param archiveIntervalMs how often to run the archival pass (ignored if {@code coldStore} is null)
     */
    public static ExchangeServer start(SessionSettings settings, String instanceName, int discoveryPort,
                                       String workDirectory, List<Instrument> instruments,
                                       ColdStore coldStore, long archiveIntervalMs) throws Exception {
        return start(settings, instanceName, discoveryPort, workDirectory, instruments,
                coldStore, archiveIntervalMs, -1, -1);
    }

    /**
     * Start, optionally with cold-data archival and the price-data feed service
     * (FxcExchange/docs/stories/001).
     *
     * @param feedHttpPort REST + web-UI port, or {@code < 0} to disable the feed service
     * @param feedWsPort   live-feed WebSocket port ({@code 0} binds ephemeral); ignored if the feed
     *                     is disabled
     */
    public static ExchangeServer start(SessionSettings settings, String instanceName, int discoveryPort,
                                       String workDirectory, List<Instrument> instruments,
                                       ColdStore coldStore, long archiveIntervalMs,
                                       int feedHttpPort, int feedWsPort) throws Exception {
        GridNode node = GridNode.start(instanceName, discoveryPort, workDirectory);
        try {
            ExchangeTables.createAll(node.ignite());
            ExchangeRepository repository = new ExchangeRepository(node.ignite());

            MatchingEngine engine = new MatchingEngine();
            MatchingEngineService matchingService = new MatchingEngineService(engine, repository);
            matchingService.seed(instruments);

            ExchangeApplication application = new ExchangeApplication(matchingService);
            MarketDataService marketDataService = new MarketDataService(engine, application);
            application.setMarketDataService(marketDataService);
            matchingService.addListener(marketDataService);

            ClearingService clearingService = new ClearingService(engine, repository);
            matchingService.addListener(clearingService);

            Acceptor acceptor = new SocketAcceptor(application, new MemoryStoreFactory(), settings,
                    new SLF4JLogFactory(settings), new DefaultMessageFactory());
            acceptor.start();

            ArchiveService archiveService = null;
            ScheduledExecutorService archiveScheduler = null;
            if (coldStore != null) {
                archiveService = new ArchiveService(node.ignite(), coldStore, System::currentTimeMillis);
                if (archiveIntervalMs > 0) {
                    ArchiveService svc = archiveService;
                    archiveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "fxc-exchange-archive");
                        t.setDaemon(true);
                        return t;
                    });
                    archiveScheduler.scheduleWithFixedDelay(() -> {
                        try {
                            svc.archiveNow();
                        } catch (Exception e) {
                            System.err.println("archive pass failed: " + e.getMessage());
                        }
                    }, archiveIntervalMs, archiveIntervalMs, TimeUnit.MILLISECONDS);
                }
            }

            FeedService feedService = null;
            if (feedHttpPort >= 0) {
                feedService = FeedService.start(node.ignite(), coldStore, System::currentTimeMillis,
                        "0.0.0.0", feedHttpPort, Math.max(feedWsPort, 0));
                matchingService.addListener(feedService.liveFeed());
            }

            return new ExchangeServer(node, matchingService, clearingService, acceptor,
                    archiveService, archiveScheduler, coldStore, feedService);
        } catch (Exception e) {
            node.close();
            throw e;
        }
    }

    /** The price-data feed service, or {@code null} if the feed is disabled. */
    public FeedService feedService() {
        return feedService;
    }

    public MatchingEngineService matchingService() {
        return matchingService;
    }

    public ClearingService clearingService() {
        return clearingService;
    }

    /** The archive service, or {@code null} if archival is disabled. */
    public ArchiveService archiveService() {
        return archiveService;
    }

    @Override
    public void close() {
        if (feedService != null) {
            feedService.close();
        }
        if (archiveScheduler != null) {
            archiveScheduler.shutdownNow();
        }
        acceptor.stop();
        node.close();
        if (coldStore != null) {
            coldStore.close();
        }
    }
}
