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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
    private OrderRouter router;

    public OmsService(AccountService accountService, BrokerRepository repository) {
        this.accountService = accountService;
        this.repository = repository;
        this.instruments = InstrumentCatalog.bySymbol();
    }

    public void setRouter(OrderRouter router) {
        this.router = router;
    }

    /** Validate, persist, and route a new client order. */
    public synchronized OrderResult submit(String account, String clientOrderId, String symbol,
                                           Side side, OrderType type, BigDecimal price, BigDecimal quantity) {
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
        return OrderResult.accepted(order);
    }

    /** Handle an inbound ExecutionReport from the exchange. */
    public synchronized void onExecutionReport(String clientOrderId, String execId, String exchangeOrderId,
                                               boolean isFill, boolean isReject, BigDecimal lastQty,
                                               BigDecimal lastPx, BigDecimal cumQty, String text) {
        ClientOrder order = orders.get(clientOrderId);
        if (order == null) {
            return; // unknown order (e.g. late report after restart) — ignore
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
            order.applyFill(lastQty, lastPx);
            accountService.applyFill(order.account(), instrument, order.side(), lastQty, lastPx);
            repository.insertExecution(new Execution(execId, clientOrderId, order.symbol(),
                    order.side(), lastQty, lastPx, order.cumQty(), order.status()));
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
        return OrderResult.rejected(order, reason);
    }
}
