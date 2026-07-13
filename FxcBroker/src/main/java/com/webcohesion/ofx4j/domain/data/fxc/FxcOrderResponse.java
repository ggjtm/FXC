package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.ResponseMessage;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.Element;

/**
 * FXC custom OFX order-entry response body ({@code FXCORDRS}): the broker order id assigned and the
 * order status at acceptance (fills arrive later and are read via the investment statement).
 */
@Aggregate("FXCORDRS")
public class FxcOrderResponse extends ResponseMessage {

    private String brokerOrderId;
    private String orderStatus;

    @Override
    public String getResponseMessageName() {
        return "FXC order";
    }

    @Element(name = "BROKERORDERID", required = true, order = 10)
    public String getBrokerOrderId() {
        return brokerOrderId;
    }

    public void setBrokerOrderId(String brokerOrderId) {
        this.brokerOrderId = brokerOrderId;
    }

    @Element(name = "ORDERSTATUS", required = true, order = 20)
    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }
}
