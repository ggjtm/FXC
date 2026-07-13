package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.ResponseMessage;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import com.webcohesion.ofx4j.meta.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * FXC custom OFX order-book snapshot response body ({@code FXCMDRS}): the instrument, the last
 * traded price (if known), and the aggregated book levels (bids + offers) sourced from the exchange
 * FIX feed (FxcBroker/docs/stories/001).
 */
@Aggregate("FXCMDRS")
public class FxcBookResponse extends ResponseMessage {

    private SecurityId securityId;
    private Double lastPrice;
    private List<FxcBookLevel> levels = new ArrayList<>();

    @Override
    public String getResponseMessageName() {
        return "FXC book";
    }

    @ChildAggregate(name = "SECID", required = true, order = 10)
    public SecurityId getSecurityId() {
        return securityId;
    }

    public void setSecurityId(SecurityId securityId) {
        this.securityId = securityId;
    }

    @Element(name = "LASTPRICE", required = false, order = 20)
    public Double getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(Double lastPrice) {
        this.lastPrice = lastPrice;
    }

    @ChildAggregate(order = 30)
    public List<FxcBookLevel> getLevels() {
        return levels;
    }

    public void setLevels(List<FxcBookLevel> levels) {
        this.levels = levels;
    }
}
