package com.fxc.exchange.book;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether the exchange is accepting orders (docs/DESIGN.md §6). Until the demo console needed a
 * "start/stop trading session" control the exchange had no notion of market state at all — the
 * matching engine accepted orders unconditionally and the FIX sessions run 24h — so this is the
 * exchange's market-state primitive.
 *
 * <p>Two independent switches: a market-wide state and a set of individually halted symbols. The
 * effective state is the <b>safer</b> of the two — a symbol trades only when the market is open
 * <i>and</i> that symbol is not individually halted. Deliberately a conjunction rather than an
 * override: a market-wide halt can never be defeated by a stale per-symbol setting, and resuming the
 * market does not silently resume a symbol an operator halted on its own.
 *
 * <p>Pure domain — no GridGain, no FIX, no HTTP. State is in memory only: a restart opens the
 * market, which is the right default for a demo component.
 */
public final class TradingSession {

    public enum State { OPEN, HALTED }

    private volatile State market = State.OPEN;
    private final Set<String> haltedSymbols = ConcurrentHashMap.newKeySet();

    /** The market-wide state, ignoring per-symbol halts. */
    public State marketState() {
        return market;
    }

    /** The effective state for one symbol. */
    public State state(String symbol) {
        return market == State.OPEN && !haltedSymbols.contains(symbol) ? State.OPEN : State.HALTED;
    }

    public boolean isHalted(String symbol) {
        return state(symbol) == State.HALTED;
    }

    /** Halt the whole market. Per-symbol halts are left as they are. */
    public void halt() {
        market = State.HALTED;
    }

    /** Reopen the market. Individually halted symbols stay halted. */
    public void open() {
        market = State.OPEN;
    }

    public void halt(String symbol) {
        haltedSymbols.add(symbol);
    }

    public void open(String symbol) {
        haltedSymbols.remove(symbol);
    }

    /** Symbols halted individually, whatever the market-wide state. */
    public Set<String> haltedSymbols() {
        return Set.copyOf(haltedSymbols);
    }
}
