package com.fxc.exchange.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.exchange.book.TradingSession.State;
import org.junit.jupiter.api.Test;

/**
 * The market-state primitive behind the console's halt/resume controls (docs/DESIGN.md §6).
 * The behaviour that matters most here is the conjunction rule: the two switches combine to the
 * safer state, so no combination of operator actions can leave a symbol trading unintentionally.
 */
class TradingSessionTest {

    @Test
    void opensByDefault() {
        TradingSession session = new TradingSession();
        assertEquals(State.OPEN, session.marketState());
        assertEquals(State.OPEN, session.state("ACME"));
        assertFalse(session.isHalted("ACME"));
        assertTrue(session.haltedSymbols().isEmpty());
    }

    @Test
    void marketHaltStopsEverySymbol() {
        TradingSession session = new TradingSession();
        session.halt();
        assertEquals(State.HALTED, session.marketState());
        assertTrue(session.isHalted("ACME"));
        assertTrue(session.isHalted("EUR/USD"));
        assertTrue(session.isHalted("anything-unlisted"));
    }

    @Test
    void symbolHaltStopsOnlyThatSymbol() {
        TradingSession session = new TradingSession();
        session.halt("ACME");
        assertTrue(session.isHalted("ACME"));
        assertFalse(session.isHalted("GLOBEX"));
        assertEquals(State.OPEN, session.marketState(), "halting a symbol is not a market halt");
        assertEquals(java.util.Set.of("ACME"), session.haltedSymbols());
    }

    @Test
    void reopeningTheMarketLeavesIndividuallyHaltedSymbolsHalted() {
        TradingSession session = new TradingSession();
        session.halt("ACME");
        session.halt();
        session.open();

        assertEquals(State.OPEN, session.marketState());
        assertTrue(session.isHalted("ACME"), "a market resume must not silently resume a halted symbol");
        assertFalse(session.isHalted("GLOBEX"));

        session.open("ACME");
        assertFalse(session.isHalted("ACME"));
    }

    @Test
    void reopeningASymbolDoesNotDefeatAMarketHalt() {
        TradingSession session = new TradingSession();
        session.halt();
        session.open("ACME");
        assertTrue(session.isHalted("ACME"), "the market-wide halt must still win");
    }

    @Test
    void haltAndOpenAreIdempotent() {
        TradingSession session = new TradingSession();
        session.halt("ACME");
        session.halt("ACME");
        assertEquals(1, session.haltedSymbols().size());
        session.open("ACME");
        session.open("ACME");
        assertTrue(session.haltedSymbols().isEmpty());
        session.halt();
        session.halt();
        assertEquals(State.HALTED, session.marketState());
    }

    @Test
    void haltedSymbolsViewIsAnImmutableSnapshot() {
        TradingSession session = new TradingSession();
        session.halt("ACME");
        java.util.Set<String> snapshot = session.haltedSymbols();
        session.halt("GLOBEX");
        assertEquals(java.util.Set.of("ACME"), snapshot, "snapshot must not track later changes");
    }
}
