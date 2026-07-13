package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.RequestMessage;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import java.util.ArrayList;
import java.util.List;

/**
 * The FXC custom order-entry request message set ({@code FXCORDMSGSRQV1}). Rides in the standard OFX
 * {@code RequestEnvelope} alongside the signon message set. Reuses the {@code investment}
 * {@link MessageSetType} slot (an order request never also carries a statement request, so there is
 * no collision in the envelope's type-ordered set).
 */
@Aggregate("FXCORDMSGSRQV1")
public class FxcOrderRequestMessageSet extends RequestMessageSet {

    private FxcOrderRequestTransaction orderRequest;

    @Override
    public MessageSetType getType() {
        return MessageSetType.investment;
    }

    @ChildAggregate(order = 0)
    public FxcOrderRequestTransaction getOrderRequest() {
        return orderRequest;
    }

    public void setOrderRequest(FxcOrderRequestTransaction orderRequest) {
        this.orderRequest = orderRequest;
    }

    @Override
    public List<RequestMessage> getRequestMessages() {
        List<RequestMessage> messages = new ArrayList<>();
        if (orderRequest != null) {
            messages.add(orderRequest);
        }
        return messages;
    }
}
