package com.fxc.common.instrument;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * A cash equity (e.g. ACME). A fill moves a share position against a cash balance. Settlement is
 * delivery-versus-payment, T+1 by convention ({@link SettlementProfile#EQUITY_DEFAULT}). No
 * corporate actions (dividends, splits) are in scope initially.
 *
 * @param symbol        ticker symbol, e.g. {@code "ACME"}
 * @param issuerName    human-readable issuer name
 * @param quoteCurrency the currency the equity is priced and settled in
 * @param tickSize      minimum price increment
 * @param lotSize       minimum quantity increment
 * @param settlement    settlement profile
 */
public record EquityInstrument(
        String symbol,
        String issuerName,
        Currency quoteCurrency,
        BigDecimal tickSize,
        BigDecimal lotSize,
        SettlementProfile settlement) implements Instrument {

    public EquityInstrument {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (quoteCurrency == null) {
            throw new IllegalArgumentException("quoteCurrency must not be null");
        }
        requirePositive(tickSize, "tickSize");
        requirePositive(lotSize, "lotSize");
        if (settlement == null) {
            throw new IllegalArgumentException("settlement must not be null");
        }
    }

    /** Equity with the standard T+1 delivery-versus-payment settlement profile. */
    public static EquityInstrument of(String symbol, String issuerName, Currency quoteCurrency,
                                      BigDecimal tickSize, BigDecimal lotSize) {
        return new EquityInstrument(symbol, issuerName, quoteCurrency, tickSize, lotSize,
                SettlementProfile.EQUITY_DEFAULT);
    }

    @Override
    public AssetClass assetClass() {
        return AssetClass.EQUITY;
    }

    private static void requirePositive(BigDecimal value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
