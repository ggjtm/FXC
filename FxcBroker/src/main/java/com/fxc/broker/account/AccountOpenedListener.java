package com.fxc.broker.account;

/**
 * Notified when an account is opened (docs/stories/004), so state that is captured "at session start"
 * can be captured for an account that did not exist then.
 *
 * <p>{@code PnlService} is the reason this exists: it records each account's starting equity as the
 * baseline every later point is measured against. An account opened mid-session has no such moment
 * unless something tells it, and without one its curve would begin at its first fill — showing the
 * first trade as the whole move.
 *
 * <p>Deliberately narrow: the account number and its owner, nothing else. A listener that needs
 * balances reads them from {@code AccountService}, which has them by the time this fires.
 */
@FunctionalInterface
public interface AccountOpenedListener {

    void onAccountOpened(String account, String ownerName);
}
