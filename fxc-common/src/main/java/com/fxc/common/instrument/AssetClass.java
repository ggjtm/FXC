package com.fxc.common.instrument;

/**
 * The asset classes FXC trades. The matching engine, OMS, and market data are written against
 * {@link Instrument} and never branch on this value; asset-class-specific behavior is confined to
 * {@link SettlementProfile} and the OFX statement mapping (see {@code docs/DESIGN.md} §3.0).
 *
 * <p>Derivatives are an explicit ToDo: adding {@code OPTION}/{@code FUTURE} here, plus the
 * matching {@link Instrument} subtypes and {@link SettlementProfile} styles, is the designated
 * extension point (see {@code docs/DESIGN.md} §6.3).
 */
public enum AssetClass {
    FX_SPOT,
    EQUITY;
    // ToDo (docs/DESIGN.md §6.3): OPTION, FUTURE
}
