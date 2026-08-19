package com.fxc.common.instrument;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The shared instrument universe (docs/DESIGN.md §4.1): twenty-five fictitious equities. Lives in
 * {@code fxc-common} so the exchange, broker, and investor all agree on the same symbols, tick/lot
 * sizes, settlement profiles, and opening prices.
 *
 * <p>{@link #LISTINGS} is the ONLY place the universe is written down. Float seeding, the resident
 * agent's symbol set, and the Locust harness all resolve "every equity" from here via
 * {@link #resolveSymbols}, so listing a new issuer never means editing a conf file, a Salt pillar,
 * or docker-compose. The one deliberate duplicate is {@code loadgen/fxc_loadgen/instruments.py},
 * which mirrors this table for the Python harness and is pinned against it by
 * {@code loadgen/tests/test_instruments.py}.
 *
 * <p>No FX pair is listed any more (fxc/docs/PROBLEMS.md P23). {@link FxSpotInstrument} and the
 * {@code CURRENCY_EXCHANGE} settlement style remain compiled and unit-tested — they are the second
 * {@link Instrument} implementation that keeps the abstraction honest — but nothing trades them.
 */
public final class InstrumentCatalog {

    private InstrumentCatalog() {
    }

    /** A listed equity: what the exchange enforces, plus the price it opens at. */
    private record Listing(String symbol, String issuerName, String referencePrice) {
    }

    private static final Currency USD = Currency.getInstance("USD");
    private static final BigDecimal EQ_TICK = new BigDecimal("0.01");
    private static final BigDecimal EQ_LOT = BigDecimal.ONE;

    /**
     * The listed universe. Each issuer carries its OWN opening price: a single price across the
     * board made every book look identical and made a wrong last-sale impossible to spot.
     */
    private static final List<Listing> LISTINGS = List.of(
            new Listing("ARVX", "Arvexa Holdings", "18.25"),
            new Listing("BLTN", "Bolton Ridge Foods", "9.60"),
            new Listing("CRVN", "Corvane Energy", "46.75"),
            new Listing("DYNL", "Dynalis Robotics", "132.40"),
            new Listing("ELXR", "Elixor Biotech", "74.15"),
            new Listing("FRTH", "Forthright Rail", "31.05"),
            new Listing("GRVT", "Gravitane Aerospace", "208.60"),
            new Listing("HLYN", "Halcyon Optics", "22.90"),
            new Listing("IVRN", "Ivarne Chemical", "63.30"),
            new Listing("JNTR", "Juniter Media", "15.45"),
            new Listing("KLSO", "Kelsoe Marine", "38.70"),
            new Listing("LUMR", "Lumaris Power", "87.25"),
            new Listing("MRDN", "Meridan Freight", "26.15"),
            new Listing("NVSK", "Novask Semiconductors", "154.80"),
            new Listing("ORBN", "Orbenta Space", "96.50"),
            new Listing("PLTH", "Palethorn Mining", "11.35"),
            new Listing("QRVN", "Qorvane Systems", "41.60"),
            new Listing("RDSN", "Redstone Analytics", "58.95"),
            new Listing("STLR", "Stellaria Foods", "19.80"),
            new Listing("TRQL", "Torquil Motors", "33.20"),
            new Listing("UPLN", "Upland Networks", "112.05"),
            new Listing("VNTA", "Ventara Pharma", "68.40"),
            new Listing("WSTB", "Westbrook Grocers", "14.70"),
            new Listing("XNTH", "Xantheon Fusion", "245.30"),
            new Listing("YRRA", "Yarrow Instruments", "29.55"));

    /**
     * Built ONCE. {@code find()} used to rebuild a map on every call, and it is called per order
     * (InvestorAgent.snapToTick) and per tick (LiquidityAwareStrategy/PatientStrategy) — at 25
     * listings that is 25 allocations on a hot path for no reason.
     */
    private static final List<Instrument> DEFAULTS = LISTINGS.stream()
            .map(l -> (Instrument) EquityInstrument.of(l.symbol(), l.issuerName(), USD, EQ_TICK, EQ_LOT))
            .toList();

    private static final Map<String, Instrument> BY_SYMBOL = DEFAULTS.stream()
            .collect(Collectors.toMap(Instrument::symbol, i -> i, (a, b) -> a, LinkedHashMap::new));

    private static final Map<String, BigDecimal> REFERENCE_PRICES = LISTINGS.stream()
            .collect(Collectors.toMap(Listing::symbol, l -> new BigDecimal(l.referencePrice()),
                    (a, b) -> a, LinkedHashMap::new));

    /** The default listed instruments. */
    public static List<Instrument> defaults() {
        return DEFAULTS;
    }

    /**
     * The default instruments keyed by symbol, preserving listing order. The returned map is
     * shared and immutable — callers read it, none mutate it.
     */
    public static Map<String, Instrument> bySymbol() {
        return BY_SYMBOL;
    }

    public static Optional<Instrument> find(String symbol) {
        return Optional.ofNullable(BY_SYMBOL.get(symbol));
    }

    /** Every listed equity symbol, in listing order. */
    public static List<String> equitySymbols() {
        return DEFAULTS.stream()
                .filter(i -> i.assetClass() == AssetClass.EQUITY)
                .map(Instrument::symbol)
                .toList();
    }

    /**
     * The price a symbol opens at, before the market has priced it: the issuer's cost basis, the
     * agents' seeded last-sale, and the mark P&amp;L falls back to.
     */
    public static Optional<BigDecimal> referencePrice(String symbol) {
        return Optional.ofNullable(REFERENCE_PRICES.get(symbol));
    }

    /**
     * Resolve a symbol spec: {@code "*"} — or blank, or absent — means every listed equity;
     * otherwise a comma-separated list, validated against the catalog.
     *
     * <p>Blank means "all", not "none", deliberately: which symbols and how many shares are
     * separate questions, and {@code seedShares = 0} already answers the second. A typo'd symbol
     * fails loudly here rather than silently seeding nothing, because the exchange rejects unknown
     * symbols asynchronously over FIX — the OFX reply still says ROUTED, which is the hardest
     * failure mode in this system to see.
     */
    public static List<String> resolveSymbols(String spec) {
        if (spec == null || spec.isBlank() || "*".equals(spec.strip())) {
            return equitySymbols();
        }
        List<String> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String symbol = part.strip();
            if (symbol.isEmpty()) {
                continue;
            }
            if (!BY_SYMBOL.containsKey(symbol)) {
                throw new IllegalArgumentException("unknown instrument '" + symbol
                        + "'; listed: " + String.join(",", equitySymbols()));
            }
            out.add(symbol);
        }
        return out.isEmpty() ? equitySymbols() : List.copyOf(out);
    }
}
