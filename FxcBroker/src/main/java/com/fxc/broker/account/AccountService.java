package com.fxc.broker.account;

import com.fxc.broker.grid.BrokerRepository;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.Position;
import com.fxc.broker.model.Side;
import com.fxc.common.instrument.AssetClass;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import com.fxc.common.instrument.InstrumentCatalog;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
    /**
     * Secondary index, account -> its own positions. {@link #positions(String)} used to filter the
     * whole flat map, which is fine at 3 symbols and quadratic-ish at 25: the console polls P&amp;L
     * once a second across every account, and at 512 accounts x 26 positions that scan ran ~13.7M
     * string comparisons per poll while holding the same lock that serializes order checks and
     * fills. Maintained ONLY through {@link #register}.
     */
    private final Map<String, Map<String, Position>> byAccount = new ConcurrentHashMap<>();
    private final List<AccountOpenedListener> openedListeners = new CopyOnWriteArrayList<>();
    private volatile AccountOpeningPolicy openingPolicy = AccountOpeningPolicy.disabled();

    public AccountService(BrokerRepository repository) {
        this.repository = repository;
    }

    /**
     * What a newly opened account starts with (docs/stories/004). Set once at startup from the
     * {@code account.*} config the seeded dev accounts already use, so an opened account and a seeded
     * one are funded identically — otherwise "why is this agent's P&amp;L shaped differently" would have
     * two possible answers.
     */
    public void configureOpening(AccountOpeningPolicy policy) {
        this.openingPolicy = policy;
    }

    /** Notified for every account opened through {@link #openAccount}. */
    public void addAccountOpenedListener(AccountOpenedListener listener) {
        openedListeners.add(listener);
    }

    /**
     * Open an account for an agent, or return the one it already has.
     *
     * <p><b>Idempotent per client id.</b> Agents restart, and a Locust investor respawning into the
     * same slot is the same trader as far as the demo is concerned; opening a second account for it
     * would fragment its P&amp;L and leave the first account stranded with a balance.
     *
     * @param clientId  stable identity of the agent (e.g. {@code investor-a}, {@code locust-3})
     * @param ownerName display name, or {@code null} to derive one from the client id
     * @return the account, and whether this call is what created it
     * @throws IllegalStateException if opening is not enabled on this broker
     */
    public synchronized OpenResult openAccount(String clientId, String ownerName) {
        AccountOpeningPolicy policy = openingPolicy;
        if (!policy.enabled()) {
            throw new IllegalStateException("account opening is disabled on this broker");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        String existing = repository.accountForClient(clientId);
        if (existing != null) {
            return new OpenResult(existing, false);
        }

        String number = nextAccountNumber(policy);
        String owner = ownerName == null || ownerName.isBlank() ? "Agent " + clientId : ownerName;
        repository.upsertAccount(number, owner, policy.baseCurrency(), clientId);
        Position cash = register(new Position(number, policy.baseCurrency(), HoldingType.CASH,
                policy.seedCash(), BigDecimal.ZERO));
        repository.upsertPosition(cash);
        // Shares in EVERY listed name, not one. A rando investor picks a symbol at random and sells
        // half the time; an account holding a single name rejects 24 of every 25 sells, and a book
        // with no ask never trades (fxc/docs/PROBLEMS.md P24). Drawn from the issuer's reserve by
        // transfer so the float stays invariant no matter how many investors spawn (P19).
        if (policy.seedShares().signum() > 0) {
            for (String symbol : policy.seedSymbols()) {
                BigDecimal basis = policy.seedSharePrice() != null
                        ? policy.seedSharePrice()
                        : InstrumentCatalog.referencePrice(symbol).orElse(BigDecimal.ZERO);
                String from = policy.seedFromAccount();
                if (from != null && shares(from, symbol).compareTo(policy.seedShares()) >= 0) {
                    transferShares(from, number, symbol, policy.seedShares());
                } else {
                    if (from != null) {
                        System.out.println("[account] issuer reserve exhausted in " + symbol
                                + "; minting " + policy.seedShares().toPlainString()
                                + " for " + number + " (the float is no longer fixed)");
                    }
                    seedShares(number, symbol, policy.seedShares(), basis);
                }
            }
        }
        for (AccountOpenedListener listener : openedListeners) {
            listener.onAccountOpened(number, owner);
        }
        return new OpenResult(number, true);
    }

    /**
     * Next free account number: the highest in use plus one, zero-padded to the seeded accounts' width.
     *
     * <p>Derived from the table rather than a counter so it survives accounts arriving by any route,
     * and so two opens in the same second cannot collide — this method is only called under the
     * instance lock.
     */
    private String nextAccountNumber(AccountOpeningPolicy policy) {
        String max = repository.maxAccountNumber();
        long next = policy.firstAccountNumber();
        if (max != null && max.chars().allMatch(Character::isDigit)) {
            next = Math.max(next, Long.parseLong(max) + 1);
        }
        return String.format("%0" + policy.accountNumberWidth() + "d", next);
    }

    /** The outcome of {@link #openAccount}: which account, and whether it is new. */
    public record OpenResult(String account, boolean opened) {
    }

    /** Create an account with initial cash balances (currency code -> amount). */
    public synchronized void seedAccount(String accountNumber, String ownerName, String baseCcy,
                                         Map<String, BigDecimal> cashByCurrency) {
        repository.upsertAccount(accountNumber, ownerName, baseCcy);
        cashByCurrency.forEach((ccy, amount) -> {
            Position p = register(new Position(accountNumber, ccy, HoldingType.CASH, amount,
                    BigDecimal.ZERO));
            repository.upsertPosition(p);
        });
    }

    public boolean accountExists(String accountNumber) {
        return repository.accountExists(accountNumber);
    }

    /**
     * Move an existing share position between accounts — how the issuer places the float with the
     * market makers at startup (docs/stories/006).
     *
     * <p>A transfer rather than two seedings, because the two are not the same statement: seeding each
     * market maker with shares <em>creates</em> them, and the demo has already been bitten once by
     * stock appearing per account (docs/PROBLEMS.md P19). Issuing a float once and moving it leaves
     * the total invariant, and the total is the number that matters.
     *
     * @throws IllegalArgumentException if the source does not hold that many shares — the float cannot
     *     be over-allocated, which is the whole point of routing it through an issuer.
     */
    public synchronized void transferShares(String from, String to, String symbol, BigDecimal quantity) {
        BigDecimal available = shares(from, symbol);
        if (available.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("issuer " + from + " holds " + available + " " + symbol
                    + ", cannot transfer " + quantity);
        }
        Position source = positions.get(Position.keyOf(from, HoldingType.SHARE, symbol));
        BigDecimal basis = source == null ? BigDecimal.ZERO : source.avgPrice();
        seedShares(from, symbol, available.subtract(quantity), basis);
        seedShares(to, symbol, shares(to, symbol).add(quantity), basis);
    }

    /**
     * The one place a Position enters the maps. Every create-or-replace goes through here so the
     * per-account index cannot drift from the flat map — note seedShares REPLACES the object rather
     * than mutating it, so the index must be overwritten, not appended to.
     */
    private Position register(Position p) {
        positions.put(p.key(), p);
        byAccount.computeIfAbsent(p.account(), a -> new ConcurrentHashMap<>()).put(p.key(), p);
        return p;
    }

    /** Seed an initial share position (dev/demo/tests). */
    public synchronized void seedShares(String account, String symbol, BigDecimal quantity, BigDecimal avgPrice) {
        Position p = register(new Position(account, symbol, HoldingType.SHARE, quantity, avgPrice));
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
        return List.copyOf(byAccount.getOrDefault(account, Map.of()).values());
    }

    /** Every account, ordered by number — the console's account list and P&amp;L series keys. */
    public List<BrokerRepository.AccountRow> accounts() {
        return repository.accounts();
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
        Position p = positions.get(key);
        if (p == null) {
            p = register(new Position(account, currency, HoldingType.CASH, BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }
        p.setQuantity(scaled(p.quantity().add(delta)));
        repository.upsertPosition(p);
    }

    private void addShares(String account, String symbol, BigDecimal deltaQty, BigDecimal price) {
        String key = Position.keyOf(account, HoldingType.SHARE, symbol);
        Position p = positions.get(key);
        if (p == null) {
            p = register(new Position(account, symbol, HoldingType.SHARE, BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }
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
