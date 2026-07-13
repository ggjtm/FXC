package com.fxc.common.instrument;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * The tradable-instrument abstraction shared by every trading component (see
 * {@code docs/DESIGN.md} §3.0). The matching engine, order model, FIX mapping, OMS, and market
 * data are written once against this interface rather than per asset class.
 *
 * <p>The hierarchy is {@code sealed}: {@link FxSpotInstrument} and {@link EquityInstrument} are
 * the only permitted implementations today. Derivatives ({@code OptionInstrument},
 * {@code FutureInstrument}) are a designed extension point, not yet implemented
 * (see {@code docs/DESIGN.md} §6.3).
 */
public sealed interface Instrument permits FxSpotInstrument, EquityInstrument {

    /** Exchange symbol, e.g. {@code "EUR/USD"} or {@code "ACME"}. */
    String symbol();

    /** The asset class, used only where asset-class-specific behavior is unavoidable. */
    AssetClass assetClass();

    /** The currency prices are expressed in. */
    Currency quoteCurrency();

    /** Minimum price increment. */
    BigDecimal tickSize();

    /** Minimum quantity increment. */
    BigDecimal lotSize();

    /** How fills become settlement obligations. */
    SettlementProfile settlement();
}
