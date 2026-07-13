package com.webcohesion.ofx4j.domain.data.fxc;

import com.webcohesion.ofx4j.domain.data.MessageSetType;
import com.webcohesion.ofx4j.domain.data.ResponseMessage;
import com.webcohesion.ofx4j.domain.data.ResponseMessageSet;
import com.webcohesion.ofx4j.meta.Aggregate;
import com.webcohesion.ofx4j.meta.ChildAggregate;
import java.util.ArrayList;
import java.util.List;

/** The FXC custom market-data (order-book) response message set ({@code FXCMDMSGSRSV1}). */
@Aggregate("FXCMDMSGSRSV1")
public class FxcBookResponseMessageSet extends ResponseMessageSet {

    private FxcBookResponseTransaction bookResponse;

    @Override
    public MessageSetType getType() {
        return MessageSetType.investment_security;
    }

    @ChildAggregate(order = 0)
    public FxcBookResponseTransaction getBookResponse() {
        return bookResponse;
    }

    public void setBookResponse(FxcBookResponseTransaction bookResponse) {
        this.bookResponse = bookResponse;
    }

    @Override
    public List<ResponseMessage> getResponseMessages() {
        List<ResponseMessage> messages = new ArrayList<>();
        if (bookResponse != null) {
            messages.add(bookResponse);
        }
        return messages;
    }
}
