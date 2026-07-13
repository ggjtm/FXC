package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.TransactionWrappedRequestMessage;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;

/** Transaction wrapper for an FXC book-snapshot request ({@code FXCMDTRNRQ}). */
@Aggregate("FXCMDTRNRQ")
public class FxcBookRequestTransaction extends TransactionWrappedRequestMessage<FxcBookRequest> {

    private FxcBookRequest message;

    @ChildAggregate(required = true, order = 30)
    public FxcBookRequest getMessage() {
        return message;
    }

    public void setMessage(FxcBookRequest message) {
        this.message = message;
    }

    @Override
    public void setWrappedMessage(FxcBookRequest message) {
        setMessage(message);
    }
}
