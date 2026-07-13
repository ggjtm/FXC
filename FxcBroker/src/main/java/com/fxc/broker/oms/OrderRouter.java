package com.fxc.broker.oms;

import com.fxc.broker.model.ClientOrder;

/** Routes an accepted client order onward to the exchange. Implemented by the FIX initiator. */
@FunctionalInterface
public interface OrderRouter {
    void route(ClientOrder order);
}
