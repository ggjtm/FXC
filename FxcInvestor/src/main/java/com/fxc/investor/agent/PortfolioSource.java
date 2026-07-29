package com.fxc.investor.agent;

import com.fxc.investor.strategy.PortfolioView;

/**
 * Where an agent's holdings come from. Satisfied by
 * {@code OfxBrokerClient::fetchPortfolio} as a method reference.
 *
 * <p>Exists as a seam so {@link PortfolioCache} can be tested without an OFX server, matching how the
 * rest of the system is wired ({@code OrderRouter}, {@code MarketDataPublisher},
 * {@code CancelReporter}, {@code FillListener}).
 */
@FunctionalInterface
public interface PortfolioSource {

    /** Fetch the current holdings for an account. May fail — callers treat it as best-effort. */
    PortfolioView fetch(String account) throws Exception;
}
