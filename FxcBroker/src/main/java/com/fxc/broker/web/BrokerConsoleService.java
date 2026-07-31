package com.fxc.broker.web;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.md.MarketDataCache;
import com.fxc.broker.oms.OmsService;
import com.fxc.broker.pnl.PnlService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * What the broker console reads and does (docs/DESIGN.md §6): a status snapshot, the last-sale
 * ticker, the accounts, their P&amp;L series, and the start/stop trading switch. Pure orchestration
 * over the existing services; JSON rendering stays in {@link BrokerWebServer}.
 */
public final class BrokerConsoleService {

    /** Whole-component status for the console's status pane. */
    public record BrokerStatus(boolean tradingEnabled, boolean exchangeConnected, int accounts,
                               long uptimeMs, long ordersRouted, long fills, long rejects) {
    }

    /** One entry in the last-sale ticker. */
    public record LastSale(String symbol, BigDecimal price) {
    }

    private final OmsService oms;
    private final AccountService accounts;
    private final MarketDataCache marketData;
    private final PnlService pnl;
    private final BooleanSupplier exchangeConnected;
    private final LongSupplier clock;
    private final long startedAt;

    public BrokerConsoleService(OmsService oms, AccountService accounts, MarketDataCache marketData,
                                PnlService pnl, BooleanSupplier exchangeConnected, LongSupplier clock) {
        this.oms = oms;
        this.accounts = accounts;
        this.marketData = marketData;
        this.pnl = pnl;
        this.exchangeConnected = exchangeConnected;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    public BrokerStatus status() {
        return new BrokerStatus(oms.tradingEnabled(), exchangeConnected.getAsBoolean(),
                accounts.accounts().size(), clock.getAsLong() - startedAt,
                oms.ordersRouted(), oms.fillCount(), oms.rejectCount());
    }

    /** Last traded price per symbol, from the broker's own market-data subscription. */
    public List<LastSale> lastSales() {
        List<LastSale> sales = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : marketData.lastPrices().entrySet()) {
            sales.add(new LastSale(entry.getKey(), entry.getValue()));
        }
        return sales;
    }

    public List<BrokerRepository.AccountRow> accounts() {
        return accounts.accounts();
    }

    /**
     * Open an account for an agent, or return the one it already has (docs/stories/004).
     *
     * <p>Unlike everything else here this is a *client* operation rather than an operator one; it
     * lives on this service because the console's HTTP server is what exposes it, and it is gated
     * separately from the operator controls.
     */
    public AccountService.OpenResult openAccount(String clientId, String ownerName) {
        return accounts.openAccount(clientId, ownerName);
    }

    public List<PnlService.AccountPnl> pnl() {
        return pnl.series();
    }

    /** Stop or resume accepting client orders. */
    public boolean setTrading(boolean enabled) {
        oms.setTradingEnabled(enabled);
        return oms.tradingEnabled();
    }
}
