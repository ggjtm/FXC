package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.ResponseMessage;
import com.webcohesion.ofx4j.domain.data.ResponseMessageSet;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import java.util.ArrayList;
import java.util.List;

/** The FXC custom order-entry response message set ({@code FXCORDMSGSRSV1}). */
@Aggregate("FXCORDMSGSRSV1")
public class FxcOrderResponseMessageSet extends ResponseMessageSet {

    private FxcOrderResponseTransaction orderResponse;

    @Override
    public MessageSetType getType() {
        return MessageSetType.investment;
    }

    @ChildAggregate(order = 0)
    public FxcOrderResponseTransaction getOrderResponse() {
        return orderResponse;
    }

    public void setOrderResponse(FxcOrderResponseTransaction orderResponse) {
        this.orderResponse = orderResponse;
    }

    @Override
    public List<ResponseMessage> getResponseMessages() {
        List<ResponseMessage> messages = new ArrayList<>();
        if (orderResponse != null) {
            messages.add(orderResponse);
        }
        return messages;
    }
}
