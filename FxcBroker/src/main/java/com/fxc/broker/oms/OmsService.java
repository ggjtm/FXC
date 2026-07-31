package com.fxc.broker.oms;

import com.fxc.broker.account.AccountService;
import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.model.ClientOrder;
import com.fxc.broker.model.Execution;
import com.fxc.broker.model.OrderStatus;
import com.fxc.broker.model.OrderType;
import com.fxc.broker.model.Side;
import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Order management (docs/DESIGN.md §4.2): validates client orders against {@link AccountService},
 * routes them to the exchange via an {@link OrderRouter} (FIX initiator), tracks order state from
 * inbound {@code ExecutionReport}s, and applies fills to positions. Asset-class agnostic — operates
 * on {@link Instrument} from the shared catalog.
 */
public final class OmsService {

    private final AccountService accountService;
    private final BrokerRepository repository;
    private final Map<String, Instrument> instruments;
    private final Map<String, ClientOrder> orders = new ConcurrentHashMap<>();
    private final List<FillListener> fillListeners = new CopyOnWriteArrayList<>();
    private final LongSupplier clock;
    private final AtomicLong routed = new AtomicLong();
    private final AtomicLong fills = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private OrderRouter router;
    /** Operator switch behind the console's start/stop trading control (docs/DESIGN.md §6). */
    private volatile boolean tradingEnabled = true;

    public OmsService(AccountService accountService, BrokerRepository repository) {
        this(accountService, repository, System::currentTimeMillis);
    }

    /** Test/DI constructor with an injectable execution-timestamp clock (epoch millis). */
    public OmsService(AccountService accountService, BrokerRepository repository, LongSupplier clock) {
        this.accountService = accountService;
        this.repository = repository;
        this.clock = clock;
        this.instruments = InstrumentCatalog.bySymbol();
    }

    public void setRouter(OrderRouter router) {
        this.router = router;
    }

    /** Notified after each fill is applied — the P&amp;L series is built from this. */
    public void addFillListener(FillListener listener) {
        fillListeners.add(listener);
    }

    /**
     * Stop or resume accepting new client orders. Independent of the exchange's own market state: the
     * broker can stand down while the exchange stays open.
     */
    public void setTradingEnabled(boolean enabled) {
        this.tradingEnabled = enabled;
    }

    public boolean tradingEnabled() {
        return tradingEnabled;
    }

    public long ordersRouted() {
        return routed.get();
    }

    public long fillCount() {
        return fills.get();
    }

    public long rejectCount() {
        return rejected.get();
    }

    /** Execution reports ignored because their id had already been applied. */
    public long duplicateReportCount() {
        return duplicateReports.get();
    }

    /** Execution reports ignored because their side disagreed with the order that id resolves to. */
    public long mismatchedReportCount() {
        return mismatchedReports.get();
    }

    /** How many recent execution ids to remember for the duplicate check. */
    private static final int MAX_REMEMBERED_EXEC_IDS = 200_000;

    private final AtomicLong duplicateReports = new AtomicLong();
    private final AtomicLong mismatchedReports = new AtomicLong();

    /** Reports already applied, so a resend cannot move balances twice. */
    private final Set<String> appliedExecIds = Collections.newSetFromMap(
            new LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    // Bounded: a demo can fill for hours, and only recent ids can plausibly be resent.
                    return size() > MAX_REMEMBERED_EXEC_IDS;
                }
            });

    /** Validate, persist, and route a new client order. */
    public synchronized OrderResult submit(String account, String clientOrderId, String symbol,
                                           Side side, OrderType type, BigDecimal price, BigDecimal quantity) {
        if (!tradingEnabled) {
            return reject(new ClientOrder(clientOrderId, account, symbol, side, type, price, quantity),
                    "trading stopped by operator");
        }
        Instrument instrument = instruments.get(symbol);
        if (instrument == null) {
            return reject(new ClientOrder(clientOrderId, account, symbol, side, type, price, quantity),
                    "unknown instrument: " + symbol);
        }
        if (quantity == null || quantity.signum() <= 0) {
            return reject(new ClientOrder(clientOrderId, account, symbol, side, type, price, quantity),
                    "quantity must be positive");
        }

        ClientOrder order = new ClientOrder(clientOrderId, account, symbol, side, type, price, quantity);
        // A client order id is this broker's primary key for an order, so a repeat is not a harmless
        // duplicate — the old mapping is what later ExecutionReports are resolved through. Overwriting
        // it silently applied one client's fills to another client's order (docs/PROBLEMS.md P18);
        // rejecting is also what a real OMS does with a reused ClOrdID.
        if (orders.containsKey(clientOrderId)) {
            // Rejected *without storing*: the normal reject path puts the order in the map and MERGEs
            // it into CLIENT_ORDER, both keyed on this id — so rejecting through it would still
            // destroy the original order it is protecting.
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason("duplicate client order id: " + clientOrderId);
            rejected.incrementAndGet();
            return OrderResult.rejected(order, order.rejectReason());
        }
        Optional<String> rejection = accountService.check(account, instrument, side, price, quantity);
        if (rejection.isPresent()) {
            return reject(order, rejection.get());
        }

        orders.put(clientOrderId, order);
        order.setStatus(OrderStatus.NEW);
        repository.upsertOrder(order);

        if (router == null) {
            return reject(order, "no exchange route available");
        }
        router.route(order);
        order.setStatus(OrderStatus.ROUTED);
        repository.upsertOrder(order);
        routed.incrementAndGet();
        return OrderResult.accepted(order);
    }

    /**
     * Handle an inbound ExecutionReport from the exchange.
     *
     * <p><b>Applied at most once per {@code execId}.</b> A fill moves real balances, so a report
     * delivered twice — a FIX resend after a reconnect, a {@code PossDupFlag} replay — would create
     * shares and destroy cash. The execution table has always deduplicated (it MERGEs on
     * {@code exec_id}), which meant a double-apply corrupted balances while leaving no trace in the
     * archive (docs/PROBLEMS.md P18).
     *
     * @param reportedSide the side the exchange says filled, or {@code null} if the report carried
     *     none. Checked against the stored order rather than trusted or ignored: a disagreement means
     *     this report belongs to a different order than the one this id resolves to, and applying it
     *     would move the wrong account's balances.
     */
    public synchronized void onExecutionReport(String clientOrderId, String execId, String exchangeOrderId,
                                               boolean isFill, boolean isReject, BigDecimal lastQty,
                                               BigDecimal lastPx, BigDecimal cumQty, String text,
                                               Side reportedSide) {
        ClientOrder order = orders.get(clientOrderId);
        if (order == null) {
            return; // unknown order (e.g. late report after restart) — ignore
        }
        if (isFill && execId != null && !appliedExecIds.add(execId)) {
            duplicateReports.incrementAndGet();
            return; // already applied; applying it again would invent shares or cash
        }
        if (isFill && reportedSide != null && reportedSide != order.side()) {
            // The exchange filled a different order than this id resolves to here. Do not guess.
            mismatchedReports.incrementAndGet();
            System.err.println("ExecutionReport for " + clientOrderId + " reports " + reportedSide
                    + " but that id is a " + order.side() + " order — ignoring, balances untouched");
            return;
        }
        if (exchangeOrderId != null) {
            order.setExchangeOrderId(exchangeOrderId);
        }
        if (isReject) {
            order.setStatus(OrderStatus.REJECTED);
            order.setRejectReason(text);
            repository.upsertOrder(order);
            return;
        }
        if (isFill && lastQty != null && lastQty.signum() > 0) {
            Instrument instrument = instruments.get(order.symbol());
            long ts = clock.getAsLong();
            order.applyFill(lastQty, lastPx);
            accountService.applyFill(order.account(), instrument, order.side(), lastQty, lastPx);
            repository.insertExecution(new Execution(execId, clientOrderId, order.account(), order.symbol(),
                    order.side(), lastQty, lastPx, order.cumQty(), order.status(), ts));
            fills.incrementAndGet();
            // After the position is updated, so a listener sees post-fill balances. An equity sell
            // leaves avg_price untouched, which is what makes the cost basis still readable here.
            for (FillListener listener : fillListeners) {
                listener.onFill(order.account(), instrument, order.side(), lastQty, lastPx, ts);
            }
        }
        repository.upsertOrder(order);
    }

    public Optional<ClientOrder> order(String clientOrderId) {
        return Optional.ofNullable(orders.get(clientOrderId));
    }

    private OrderResult reject(ClientOrder order, String reason) {
        order.setStatus(OrderStatus.REJECTED);
        order.setRejectReason(reason);
        orders.put(order.clientOrderId(), order);
        repository.upsertOrder(order);
        rejected.incrementAndGet();
        return OrderResult.rejected(order, reason);
    }
}
