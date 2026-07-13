package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.RequestMessage;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import java.util.ArrayList;
import java.util.List;

/**
 * The FXC custom market-data (order-book) request message set ({@code FXCMDMSGSRQV1}). Uses the
 * {@code investment_security} {@link MessageSetType} slot so it never collides with the order-entry
 * set (which uses {@code investment}) in a shared envelope.
 */
@Aggregate("FXCMDMSGSRQV1")
public class FxcBookRequestMessageSet extends RequestMessageSet {

    private FxcBookRequestTransaction bookRequest;

    @Override
    public MessageSetType getType() {
        return MessageSetType.investment_security;
    }

    @ChildAggregate(order = 0)
    public FxcBookRequestTransaction getBookRequest() {
        return bookRequest;
    }

    public void setBookRequest(FxcBookRequestTransaction bookRequest) {
        this.bookRequest = bookRequest;
    }

    @Override
    public List<RequestMessage> getRequestMessages() {
        List<RequestMessage> messages = new ArrayList<>();
        if (bookRequest != null) {
            messages.add(bookRequest);
        }
        return messages;
    }
}
