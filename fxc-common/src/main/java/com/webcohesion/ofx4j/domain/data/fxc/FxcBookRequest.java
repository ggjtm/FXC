package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.RequestMessage;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import com.webcohesion.ofx4j.meta.Element;

/**
 * FXC custom OFX order-book snapshot request body ({@code FXCMDRQ}) — an investor asks the broker
 * for the top-{@code depth} of the order book for an instrument (FxcBroker/docs/stories/001). The
 * broker sources it from FxcExchange's FIX market-data feed. References the instrument by the same
 * {@link SecurityId} used in statements/orders.
 */
@Aggregate("FXCMDRQ")
public class FxcBookRequest extends RequestMessage {

    private SecurityId securityId;
    private Integer depth;

    @ChildAggregate(name = "SECID", required = true, order = 10)
    public SecurityId getSecurityId() {
        return securityId;
    }

    public void setSecurityId(SecurityId securityId) {
        this.securityId = securityId;
    }

    @Element(name = "DEPTH", required = true, order = 20)
    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }
}
