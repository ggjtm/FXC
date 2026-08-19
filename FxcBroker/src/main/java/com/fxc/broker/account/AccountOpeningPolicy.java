package com.fxc.broker.account;

import java.math.BigDecimal;
import java.util.List;

/**
 * What an agent's account starts with when it opens one (docs/stories/004).
 *
 * <p><b>Shares come from the issuer's reserve, never from thin air.</b> An opened account that
 * <em>minted</em> its shares inflated the float instead of bringing capital to it, and since nobody
 * in this market can be short, a growing float with a fixed appetite can only push the price down —
 * 260 agents injected 260,000 shares against a 2,000-share float and the price fell 23%
 * (docs/PROBLEMS.md P19). The answer used to be "open cash-only accounts". That stopped working
 * when the universe grew to 25 listings: a rando investor picks a symbol at random and sells half
 * the time, so a cash-only account rejects every sell until it happens to buy something, and a book
 * with no ask never trades at all (fxc/docs/PROBLEMS.md P24).
 *
 * <p>So opened accounts now DO carry stock — drawn from {@link #seedFromAccount} by transfer, which
 * keeps the total invariant exactly as the issuer/market-maker seeding does. Every book has two
 * sides from the first tick, and the float is still a number somebody chose.
 *
 * <p>The cash figure comes from the same {@code account.*} keys that fund the seeded dev accounts, so
 * every agent starts with the same purchasing power. That matters for the console: every curve on it
 * is P&amp;L relative to a starting equity, and two different starting equities would make two agents'
 * curves incomparable for a reason invisible on the chart.
 *
 * @param enabled            whether this broker opens accounts at all
 * @param baseCurrency       currency of the opening cash balance
 * @param seedCash           opening cash
 * @param seedSymbols        equities to seed — every listed symbol by default; empty for cash only
 * @param seedShares         how many shares of EACH; {@code 0} means cash only
 * @param seedSharePrice     cost basis override, or {@code null} to use each symbol's catalog
 *                           reference price (the only sane default once symbols differ in price)
 * @param seedFromAccount    account the shares are transferred out of, or {@code null} to mint them
 * @param firstAccountNumber lowest number an opened account may take; keeps them clear of the seeded
 *                           dev accounts, so an account number says where it came from
 * @param accountNumberWidth zero-padded width, matching the seeded accounts' format
 */
public record AccountOpeningPolicy(boolean enabled, String baseCurrency, BigDecimal seedCash,
                                   List<String> seedSymbols, BigDecimal seedShares,
                                   BigDecimal seedSharePrice, String seedFromAccount,
                                   long firstAccountNumber, int accountNumberWidth) {

    public AccountOpeningPolicy {
        seedSymbols = seedSymbols == null ? List.of() : List.copyOf(seedSymbols);
    }

    /** No opening: the broker serves only the accounts it was seeded with. */
    public static AccountOpeningPolicy disabled() {
        return new AccountOpeningPolicy(false, "USD", BigDecimal.ZERO, List.of(), BigDecimal.ZERO,
                null, null, 100_000L, 9);
    }
}
