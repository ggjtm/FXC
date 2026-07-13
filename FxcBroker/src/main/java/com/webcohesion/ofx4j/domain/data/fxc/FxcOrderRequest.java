package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.RequestMessage;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import com.webcohesion.ofx4j.meta.Element;

/**
 * FXC custom OFX order-entry request body ({@code FXCORDRQ}). OFX 2.x has no native order-entry
 * messages, so this is a private message set (docs/DESIGN.md §4.2, §6.4). It references instruments
 * by the same {@link SecurityId} used in statements (equity CUSIP-style id, or synthetic FX
 * {@code FX:EURUSD}) so orders and positions line up.
 *
 * <p><b>Package note:</b> these classes deliberately live under {@code com.webcohesion.ofx4j.*} so
 * OFX4J's {@code AggregateIntrospector} (which classpath-scans only that package) can resolve them
 * when unmarshalling — see PROBLEMS.md P4 and {@code .reference/ofx/ofx4j-usage.md}.
 */
@Aggregate("FXCORDRQ")
public class FxcOrderRequest extends RequestMessage {

    private String accountId;
    private SecurityId securityId;
    private String side;        // BUY | SELL
    private Double units;
    private String orderType;   // LIMIT | MARKET
    private Double limitPrice;

    @Element(name = "ACCTID", required = true, order = 10)
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    @ChildAggregate(name = "SECID", required = true, order = 20)
    public SecurityId getSecurityId() {
        return securityId;
    }

    public void setSecurityId(SecurityId securityId) {
        this.securityId = securityId;
    }

    @Element(name = "SIDE", required = true, order = 30)
    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    @Element(name = "UNITS", required = true, order = 40)
    public Double getUnits() {
        return units;
    }

    public void setUnits(Double units) {
        this.units = units;
    }

    @Element(name = "ORDERTYPE", required = true, order = 50)
    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType = orderType;
    }

    @Element(name = "LIMITPRICE", required = false, order = 60)
    public Double getLimitPrice() {
        return limitPrice;
    }

    public void setLimitPrice(Double limitPrice) {
        this.limitPrice = limitPrice;
    }
}
