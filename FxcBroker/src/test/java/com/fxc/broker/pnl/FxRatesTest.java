package com.fxc.broker.pnl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fxc.broker.md.MarketDataCache;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * USD conversion for the console's single P&amp;L axis (docs/DESIGN.md §6). The behaviour that matters
 * is that an unresolvable currency returns empty rather than a guess — the caller reports those as a
 * disclosed gap instead of silently valuing a balance at zero.
 */
class FxRatesTest {

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
    }

    @Test
    void usdIsUnity() {
        FxRates rates = new FxRates(new MarketDataCache());
        eq("1", rates.toUsd("USD").orElseThrow());
    }

    @Test
    void usesTheDirectPairWhenItHasTraded() {
        MarketDataCache md = new MarketDataCache();
        md.setLastPrice("EUR/USD", new BigDecimal("1.08500"));
        FxRates rates = new FxRates(md);
        eq("1.08500", rates.toUsd("EUR").orElseThrow());
        eq("1085.00000", rates.convert(new BigDecimal("1000"), "EUR").orElseThrow());
    }

    @Test
    void invertsTheReciprocalPairWhenOnlyThatHasTraded() {
        // The seeded universe quotes JPY as USD/JPY only, so it can only be reached by inversion.
        MarketDataCache md = new MarketDataCache();
        md.setLastPrice("USD/JPY", new BigDecimal("156.25"));
        FxRates rates = new FxRates(md);
        eq("0.0064", rates.toUsd("JPY").orElseThrow());
        eq("64", rates.convert(new BigDecimal("10000"), "JPY").orElseThrow());
    }

    @Test
    void prefersTheDirectPairOverTheInverse() {
        MarketDataCache md = new MarketDataCache();
        md.setLastPrice("EUR/USD", new BigDecimal("1.08500"));
        md.setLastPrice("USD/EUR", new BigDecimal("0.5"));
        eq("1.08500", new FxRates(md).toUsd("EUR").orElseThrow());
    }

    @Test
    void unknownCurrencyIsEmptyNotAGuess() {
        FxRates rates = new FxRates(new MarketDataCache());
        assertTrue(rates.toUsd("CHF").isEmpty(), "no pair has traded, so there is no rate");
        assertTrue(rates.convert(new BigDecimal("100"), "CHF").isEmpty());
    }

    @Test
    void aZeroRateIsTreatedAsNoRate() {
        // A zero mark would otherwise value the whole balance at zero, or divide by zero on inversion.
        MarketDataCache md = new MarketDataCache();
        md.setLastPrice("EUR/USD", BigDecimal.ZERO);
        md.setLastPrice("USD/JPY", BigDecimal.ZERO);
        FxRates rates = new FxRates(md);
        assertTrue(rates.toUsd("EUR").isEmpty());
        assertTrue(rates.toUsd("JPY").isEmpty());
    }
}
