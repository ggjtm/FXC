package com.fxc.common.instrument;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * An FX spot currency pair (e.g. EUR/USD). A fill moves two currency balances: buying EUR/USD is
 * {@code +EUR, -USD}. Settlement is bilateral currency exchange, T+2 by convention
 * ({@link SettlementProfile#FX_SPOT_DEFAULT}).
 *
 * @param baseCurrency  the base currency (the "EUR" in EUR/USD); the quantity is in this currency
 * @param quoteCurrency the quote currency (the "USD" in EUR/USD); prices are expressed in this
 * @param tickSize      minimum price increment
 * @param lotSize       minimum quantity increment
 * @param settlement    settlement profile
 */
public record FxSpotInstrument(
        Currency baseCurrency,
        Currency quoteCurrency,
        BigDecimal tickSize,
        BigDecimal lotSize,
        SettlementProfile settlement) implements Instrument {

    public FxSpotInstrument {
        if (baseCurrency == null || quoteCurrency == null) {
            throw new IllegalArgumentException("base and quote currency must not be null");
        }
        if (baseCurrency.equals(quoteCurrency)) {
            throw new IllegalArgumentException("base and quote currency must differ");
        }
        requirePositive(tickSize, "tickSize");
        requirePositive(lotSize, "lotSize");
        if (settlement == null) {
            throw new IllegalArgumentException("settlement must not be null");
        }
    }

    /** FX pair with the standard T+2 currency-exchange settlement profile. */
    public static FxSpotInstrument of(Currency base, Currency quote, BigDecimal tickSize, BigDecimal lotSize) {
        return new FxSpotInstrument(base, quote, tickSize, lotSize, SettlementProfile.FX_SPOT_DEFAULT);
    }

    @Override
    public String symbol() {
        return baseCurrency.getCurrencyCode() + "/" + quoteCurrency.getCurrencyCode();
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.FX_SPOT;
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
