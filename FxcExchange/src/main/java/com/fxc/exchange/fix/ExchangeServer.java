package com.fxc.exchange.fix;

import com.fxc.common.instrument.Instrument;
import com.fxc.exchange.book.MatchingEngine;
import com.fxc.exchange.grid.ExchangeRepository;
import com.fxc.exchange.grid.ExchangeTables;
import com.fxc.exchange.grid.GridNode;
import com.fxc.exchange.service.ClearingService;
import com.fxc.exchange.service.MarketDataService;
import com.fxc.exchange.service.MatchingEngineService;
import java.util.List;
import quickfix.Acceptor;
import quickfix.DefaultMessageFactory;
import quickfix.MemoryStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/**
 * Assembles and runs a complete FxcExchange: an embedded GridGain node with the exchange tables,
 * the matching/market-data/clearing services, and a QuickFIX/J acceptor. One object owns the whole
 * component lifecycle (start via {@link #start}, stop via {@link #close}).
 */
public final class ExchangeServer implements AutoCloseable {

    private final GridNode node;
    private final MatchingEngineService matchingService;
    private final ClearingService clearingService;
    private final Acceptor acceptor;

    private ExchangeServer(GridNode node, MatchingEngineService matchingService,
                           ClearingService clearingService, Acceptor acceptor) {
        this.node = node;
        this.matchingService = matchingService;
        this.clearingService = clearingService;
        this.acceptor = acceptor;
    }

    public static ExchangeServer start(SessionSettings settings, String instanceName, int discoveryPort,
                                       String workDirectory, List<Instrument> instruments) throws Exception {
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

            return new ExchangeServer(node, matchingService, clearingService, acceptor);
        } catch (Exception e) {
            node.close();
            throw e;
        }
    }

    public MatchingEngineService matchingService() {
        return matchingService;
    }

    public ClearingService clearingService() {
        return clearingService;
    }

    @Override
    public void close() {
        acceptor.stop();
        node.close();
    }
}
