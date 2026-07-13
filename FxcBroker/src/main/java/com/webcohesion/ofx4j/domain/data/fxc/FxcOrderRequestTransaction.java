package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.TransactionWrappedRequestMessage;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;

/**
 * Transaction wrapper for an FXC order request ({@code FXCORDTRNRQ}). The {@code TRNUID} (from the
 * base) doubles as the client order id (ClOrdID) so orders correlate across OFX and FIX.
 */
@Aggregate("FXCORDTRNRQ")
public class FxcOrderRequestTransaction extends TransactionWrappedRequestMessage<FxcOrderRequest> {

    private FxcOrderRequest message;

    @ChildAggregate(required = true, order = 30)
    public FxcOrderRequest getMessage() {
        return message;
    }

    public void setMessage(FxcOrderRequest message) {
        this.message = message;
    }

    @Override
    public void setWrappedMessage(FxcOrderRequest message) {
        setMessage(message);
    }
}
