package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.TransactionWrappedResponseMessage;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;

/** Transaction wrapper for an FXC book-snapshot response ({@code FXCMDTRNRS}). */
@Aggregate("FXCMDTRNRS")
public class FxcBookResponseTransaction extends TransactionWrappedResponseMessage<FxcBookResponse> {

    private FxcBookResponse message;

    @ChildAggregate(required = true, order = 30)
    public FxcBookResponse getMessage() {
        return message;
    }

    public void setMessage(FxcBookResponse message) {
        this.message = message;
    }

    @Override
    public FxcBookResponse getWrappedMessage() {
        return message;
    }
}
