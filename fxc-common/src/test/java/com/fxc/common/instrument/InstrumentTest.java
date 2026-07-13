package com.fxc.common.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class InstrumentTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void fxSpotDerivesSymbolAndAssetClass() {
        FxSpotInstrument eurusd =
                FxSpotInstrument.of(EUR, USD, new BigDecimal("0.00001"), new BigDecimal("1000"));

        assertEquals("EUR/USD", eurusd.symbol());
        assertEquals(AssetClass.FX_SPOT, eurusd.assetClass());
        assertEquals(USD, eurusd.quoteCurrency());
        assertSame(SettlementProfile.FX_SPOT_DEFAULT, eurusd.settlement());
        assertEquals(SettlementProfile.SettlementStyle.CURRENCY_EXCHANGE, eurusd.settlement().style());
        assertEquals(2, eurusd.settlement().settlementLagDays());
    }

    @Test
    void equityUsesTickerAsSymbolAndDvpSettlement() {
        EquityInstrument acme =
                EquityInstrument.of("ACME", "Acme Corp", USD, new BigDecimal("0.01"), BigDecimal.ONE);

        assertEquals("ACME", acme.symbol());
        assertEquals(AssetClass.EQUITY, acme.assetClass());
        assertEquals(SettlementProfile.SettlementStyle.DELIVERY_VERSUS_PAYMENT, acme.settlement().style());
        assertEquals(1, acme.settlement().settlementLagDays());
    }

    @Test
    void instrumentIsSealedAndPatternMatchable() {
        Instrument instrument =
                FxSpotInstrument.of(EUR, USD, new BigDecimal("0.00001"), new BigDecimal("1000"));

        String kind = switch (instrument) {
            case FxSpotInstrument fx -> "fx:" + fx.baseCurrency().getCurrencyCode();
            case EquityInstrument eq -> "eq:" + eq.symbol();
        };
        assertEquals("fx:EUR", kind);
    }

    @Test
    void fxRejectsIdenticalBaseAndQuote() {
        assertThrows(IllegalArgumentException.class,
                () -> FxSpotInstrument.of(USD, USD, new BigDecimal("0.01"), BigDecimal.ONE));
    }

    @Test
    void positiveIncrementsAreEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> EquityInstrument.of("ACME", "Acme Corp", USD, BigDecimal.ZERO, BigDecimal.ONE));
    }
}
