package com.fxc.broker.oms;

import com.fxc.broker.model.ClientOrder;

/** Outcome of submitting a client order to the OMS. */
public record OrderResult(boolean accepted, ClientOrder order, String reason) {

    public static OrderResult accepted(ClientOrder order) {
        return new OrderResult(true, order, null);
    }

    public static OrderResult rejected(ClientOrder order, String reason) {
        return new OrderResult(false, order, reason);
    }
}
