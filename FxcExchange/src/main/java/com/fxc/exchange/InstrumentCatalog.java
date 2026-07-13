package com.fxc.exchange;

import com.fxc.common.instrument.EquityInstrument;
import com.fxc.common.instrument.FxSpotInstrument;
import com.fxc.common.instrument.Instrument;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

/**
 * The instruments the exchange lists. The initial universe (docs/DESIGN.md §4.1) is a handful of
 * FX spot pairs plus fictional equities. Kept as a code-defined default for Phase 1; loading the
 * set from a config file is a straightforward extension (the engine/table seeding is
 * config-source agnostic).
 */
public final class InstrumentCatalog {

    private InstrumentCatalog() {
    }

    private static Currency ccy(String code) {
        return Currency.getInstance(code);
    }

    /** The default listed instruments: four FX pairs and three equities. */
    public static List<Instrument> defaults() {
        BigDecimal fxLot = new BigDecimal("1000");
        BigDecimal pipTick = new BigDecimal("0.00001");   // 5-dp majors
        BigDecimal jpyTick = new BigDecimal("0.001");     // JPY pairs quote to 3 dp
        BigDecimal eqTick = new BigDecimal("0.01");
        BigDecimal eqLot = BigDecimal.ONE;

        return List.of(
                FxSpotInstrument.of(ccy("EUR"), ccy("USD"), pipTick, fxLot),
                FxSpotInstrument.of(ccy("GBP"), ccy("USD"), pipTick, fxLot),
                FxSpotInstrument.of(ccy("USD"), ccy("JPY"), jpyTick, fxLot),
                FxSpotInstrument.of(ccy("AUD"), ccy("USD"), pipTick, fxLot),
                EquityInstrument.of("ACME", "Acme Corporation", ccy("USD"), eqTick, eqLot),
                EquityInstrument.of("GLOBEX", "Globex Corporation", ccy("USD"), eqTick, eqLot),
                EquityInstrument.of("INITECH", "Initech LLC", ccy("USD"), eqTick, eqLot));
    }
}
