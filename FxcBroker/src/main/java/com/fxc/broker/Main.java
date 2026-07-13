package com.fxc.broker;

/**
 * FxcBroker: a minimal OFX brokerage account implementation with an OMS.
 * Connects to FxcPub and FxcExchange via FIX, and accepts OFX connections
 * from FxcInvestor instances.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("FxcBroker starting...");
    }
}
