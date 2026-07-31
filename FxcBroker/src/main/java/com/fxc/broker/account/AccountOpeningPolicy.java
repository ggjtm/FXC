package com.fxc.broker.account;

import java.math.BigDecimal;

/**
 * What an agent's account starts with when it opens one (docs/stories/004).
 *
 * <p><b>Cash only by default</b> ({@code seedShares = 0}). An opened account that also carried shares
 * would <em>mint</em> them: adding investors to a running demo inflated the float instead of bringing
 * capital to it, and since nobody in this market can be short, a growing float with a fixed appetite
 * can only push the price down — 260 agents injected 260,000 shares against a 2,000-share float and
 * the price fell 23% (docs/PROBLEMS.md P19). The float belongs to the seeded dev accounts; investors
 * arrive with money and have to bid for it.
 *
 * <p>The cash figure comes from the same {@code account.*} keys that fund the seeded dev accounts, so
 * every agent starts with the same purchasing power. That matters for the console: every curve on it
 * is P&amp;L relative to a starting equity, and two different starting equities would make two agents'
 * curves incomparable for a reason invisible on the chart.
 *
 * @param enabled            whether this broker opens accounts at all
 * @param baseCurrency       currency of the opening cash balance
 * @param seedCash           opening cash
 * @param seedSymbol         equity to seed, or {@code null} for cash only
 * @param seedShares         how many shares of it; {@code 0} — the default — means <b>cash only</b>,
 *                           which is what keeps the float fixed as investors are added
 * @param seedSharePrice     their cost basis — the mark used until the market prices them
 * @param firstAccountNumber lowest number an opened account may take; keeps them clear of the seeded
 *                           dev accounts, so an account number says where it came from
 * @param accountNumberWidth zero-padded width, matching the seeded accounts' format
 */
public record AccountOpeningPolicy(boolean enabled, String baseCurrency, BigDecimal seedCash,
                                   String seedSymbol, BigDecimal seedShares, BigDecimal seedSharePrice,
                                   long firstAccountNumber, int accountNumberWidth) {

    /** No opening: the broker serves only the accounts it was seeded with. */
    public static AccountOpeningPolicy disabled() {
        return new AccountOpeningPolicy(false, "USD", BigDecimal.ZERO, null, BigDecimal.ZERO,
                BigDecimal.ZERO, 100_000L, 9);
    }
}
