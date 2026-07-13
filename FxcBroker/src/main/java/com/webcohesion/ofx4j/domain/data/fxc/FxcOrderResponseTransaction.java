package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.TransactionWrappedResponseMessage;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;

/**
 * Transaction wrapper for an FXC order response ({@code FXCORDTRNRS}). Echoes the request
 * {@code TRNUID} (client order id) and carries a {@code STATUS} (accept/reject) from the base.
 */
@Aggregate("FXCORDTRNRS")
public class FxcOrderResponseTransaction extends TransactionWrappedResponseMessage<FxcOrderResponse> {

    private FxcOrderResponse message;

    @ChildAggregate(required = true, order = 30)
    public FxcOrderResponse getMessage() {
        return message;
    }

    public void setMessage(FxcOrderResponse message) {
        this.message = message;
    }

    @Override
    public FxcOrderResponse getWrappedMessage() {
        return message;
    }
}
