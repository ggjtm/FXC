package com.fxc.common.instrument;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared instrument universe (docs/DESIGN.md §4.1): four FX spot pairs plus fictional equities.
 * Lives in {@code fxc-common} so the exchange, broker, and investor all agree on the same symbols,
 * tick/lot sizes, and settlement profiles. Code-defined default for now; loading from config is a
 * straightforward extension.
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

    /** The default instruments keyed by symbol, preserving order. */
    public static Map<String, Instrument> bySymbol() {
        Map<String, Instrument> map = new LinkedHashMap<>();
        for (Instrument instrument : defaults()) {
            map.put(instrument.symbol(), instrument);
        }
        return map;
    }

    public static Optional<Instrument> find(String symbol) {
        return Optional.ofNullable(bySymbol().get(symbol));
    }
}
