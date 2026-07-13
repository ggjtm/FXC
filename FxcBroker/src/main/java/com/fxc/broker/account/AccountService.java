package com.fxc.broker.account;

import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.Position;
import com.fxc.broker.model.Side;
import com.fxc.common.instrument.AssetClass;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cash balances (per currency) and share positions in the unified {@code POSITION} model
 * (docs/DESIGN.md §3.0/§4.2). In-memory maps are the working store; every mutation is mirrored to
 * the GridGain {@code POSITION} table via {@link BrokerRepository}. Pre-trade checks: simple full
 * funding for FX, cash-up-front for equity buys and share-availability for equity sells.
 */
public final class AccountService {

    private final BrokerRepository repository;
    // position key -> Position (authoritative working store; mirrored to the POSITION table)
    private final Map<String, Position> positions = new ConcurrentHashMap<>();

    public AccountService(BrokerRepository repository) {
        this.repository = repository;
    }

    /** Create an account with initial cash balances (currency code -> amount). */
    public synchronized void seedAccount(String accountNumber, String ownerName, String baseCcy,
                                         Map<String, BigDecimal> cashByCurrency) {
        repository.upsertAccount(accountNumber, ownerName, baseCcy);
        cashByCurrency.forEach((ccy, amount) -> {
            Position p = new Position(accountNumber, ccy, HoldingType.CASH, amount, BigDecimal.ZERO);
            positions.put(p.key(), p);
            repository.upsertPosition(p);
        });
    }

    public boolean accountExists(String accountNumber) {
        return repository.accountExists(accountNumber);
    }

    /** Seed an initial share position (dev/demo/tests). */
    public synchronized void seedShares(String account, String symbol, BigDecimal quantity, BigDecimal avgPrice) {
        Position p = new Position(account, symbol, HoldingType.SHARE, quantity, avgPrice);
        positions.put(p.key(), p);
        repository.upsertPosition(p);
    }

    /**
     * Pre-trade check. Returns a rejection reason, or empty if the order is acceptable.
     * MARKET orders skip the notional cash check (price unknown up front) but equity sells still
     * require sufficient shares.
     */
    public synchronized Optional<String> check(String account, Instrument instrument, Side side,
                                               BigDecimal price, BigDecimal quantity) {
        if (!repository.accountExists(account)) {
            return Optional.of("unknown account: " + account);
        }
        boolean marketable = price == null;
        BigDecimal notional = marketable ? null : price.multiply(quantity);

        if (instrument.assetClass() == AssetClass.EQUITY) {
            if (side == Side.BUY) {
                if (notional != null && balance(account, instrument.quoteCurrency().getCurrencyCode())
                        .compareTo(notional) < 0) {
                    return Optional.of("insufficient cash for equity buy");
                }
            } else { // SELL
                if (shares(account, instrument.symbol()).compareTo(quantity) < 0) {
                    return Optional.of("insufficient shares for equity sell (no shorting)");
                }
            }
        } else { // FX_SPOT — simple full funding of the pay-side currency
            FxSpotInstrument fx = (FxSpotInstrument) instrument;
            if (side == Side.BUY) { // pay quote currency
                if (notional != null && balance(account, fx.quoteCurrency().getCurrencyCode())
                        .compareTo(notional) < 0) {
                    return Optional.of("insufficient " + fx.quoteCurrency().getCurrencyCode() + " for FX buy");
                }
            } else { // deliver base currency
                if (balance(account, fx.baseCurrency().getCurrencyCode()).compareTo(quantity) < 0) {
                    return Optional.of("insufficient " + fx.baseCurrency().getCurrencyCode() + " for FX sell");
                }
            }
        }
        return Optional.empty();
    }

    private static final int SCALE = 8;

    private static BigDecimal scaled(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** Apply a fill to the given account's balances/positions. */
    public synchronized void applyFill(String account, Instrument instrument, Side side,
                                       BigDecimal lastQty, BigDecimal lastPx) {
        BigDecimal cash = lastQty.multiply(lastPx);
        if (instrument.assetClass() == AssetClass.EQUITY) {
            String quote = instrument.quoteCurrency().getCurrencyCode();
            if (side == Side.BUY) {
                addShares(account, instrument.symbol(), lastQty, lastPx);
                adjustCash(account, quote, cash.negate());
            } else {
                addShares(account, instrument.symbol(), lastQty.negate(), lastPx);
                adjustCash(account, quote, cash);
            }
        } else {
            FxSpotInstrument fx = (FxSpotInstrument) instrument;
            String base = fx.baseCurrency().getCurrencyCode();
            String quote = fx.quoteCurrency().getCurrencyCode();
            if (side == Side.BUY) {
                adjustCash(account, base, lastQty);
                adjustCash(account, quote, cash.negate());
            } else {
                adjustCash(account, base, lastQty.negate());
                adjustCash(account, quote, cash);
            }
        }
    }

    public synchronized List<Position> positions(String account) {
        return positions.values().stream().filter(p -> p.account().equals(account)).toList();
    }

    public synchronized BigDecimal balance(String account, String currency) {
        Position p = positions.get(Position.keyOf(account, HoldingType.CASH, currency));
        return p == null ? BigDecimal.ZERO : p.quantity();
    }

    public synchronized BigDecimal shares(String account, String symbol) {
        Position p = positions.get(Position.keyOf(account, HoldingType.SHARE, symbol));
        return p == null ? BigDecimal.ZERO : p.quantity();
    }

    private void adjustCash(String account, String currency, BigDecimal delta) {
        String key = Position.keyOf(account, HoldingType.CASH, currency);
        Position p = positions.computeIfAbsent(key,
                k -> new Position(account, currency, HoldingType.CASH, BigDecimal.ZERO, BigDecimal.ZERO));
        p.setQuantity(scaled(p.quantity().add(delta)));
        repository.upsertPosition(p);
    }

    private void addShares(String account, String symbol, BigDecimal deltaQty, BigDecimal price) {
        String key = Position.keyOf(account, HoldingType.SHARE, symbol);
        Position p = positions.computeIfAbsent(key,
                k -> new Position(account, symbol, HoldingType.SHARE, BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal newQty = p.quantity().add(deltaQty);
        if (deltaQty.signum() > 0) {
            // volume-weighted average cost on buys
            BigDecimal cost = p.quantity().multiply(p.avgPrice()).add(deltaQty.multiply(price));
            p.setAvgPrice(newQty.signum() == 0 ? BigDecimal.ZERO : cost.divide(newQty, SCALE, RoundingMode.HALF_UP));
        }
        p.setQuantity(scaled(newQty));
        repository.upsertPosition(p);
    }
}
