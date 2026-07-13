package com.fxc.broker;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.grid.BrokerTables;
import com.fxc.broker.grid.GridNode;
import com.fxc.broker.ofx.OfxHttpServer;
import com.fxc.broker.ofx.OfxService;
import com.fxc.broker.oms.BrokerFixClient;
import com.fxc.broker.oms.OmsService;
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
    private final OfxHttpServer ofxServer;

    private BrokerServer(GridNode node, AccountService accountService, OmsService omsService,
                         BrokerFixClient fixClient, OfxHttpServer ofxServer) {
        this.node = node;
        this.accountService = accountService;
        this.omsService = omsService;
        this.fixClient = fixClient;
        this.ofxServer = ofxServer;
    }

    public static BrokerServer start(String gridInstanceName, int gridDiscoveryPort, String workDir,
                                     SessionSettings fixInitiatorSettings,
                                     String ofxHost, int ofxPort, String ofxUser, String ofxPassword,
                                     String brokerId, Consumer<AccountService> seeder) throws Exception {
        GridNode node = GridNode.start(gridInstanceName, gridDiscoveryPort, workDir);
        try {
            BrokerTables.createAll(node.ignite());
            BrokerRepository repository = new BrokerRepository(node.ignite());
            AccountService accountService = new AccountService(repository);
            seeder.accept(accountService);

            OmsService omsService = new OmsService(accountService, repository);
            BrokerFixClient fixClient = new BrokerFixClient(fixInitiatorSettings, omsService);
            omsService.setRouter(fixClient);
            fixClient.start();
            // Best-effort: wait for the exchange session so routing works immediately.
            fixClient.awaitLogon(15, TimeUnit.SECONDS);

            OfxService ofxService = new OfxService(omsService, accountService, ofxUser, ofxPassword, brokerId);
            OfxHttpServer ofxServer = new OfxHttpServer(ofxHost, ofxPort, "/ofx", ofxService);
            ofxServer.start();

            return new BrokerServer(node, accountService, omsService, fixClient, ofxServer);
        } catch (Exception e) {
            node.close();
            throw e;
        }
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
        ofxServer.close();
        fixClient.close();
        node.close();
    }
}
