package com.fxc.common.ofx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.domain.data.ResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.common.Status;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookLevel;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookRequest;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookRequestTransaction;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookResponse;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookResponseMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcBookResponseTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Verifies the custom order-book message set (request + response with repeating levels) survives an
 * OFX 2.x marshal → unmarshal round trip. The repeating {@code FXCBOOKLVL} collection is the risky
 * part (FxcBroker/docs/stories/001).
 */
class BookMessageSetRoundTripTest {

    @Test
    void bookRequestRoundTrips() throws Exception {
        SecurityId secId = new SecurityId();
        secId.setUniqueId("ACME");
        secId.setUniqueIdType("TICKER");

        FxcBookRequest body = new FxcBookRequest();
        body.setSecurityId(secId);
        body.setDepth(5);
        FxcBookRequestTransaction txn = new FxcBookRequestTransaction();
        txn.setUID("BK-1");
        txn.setWrappedMessage(body);
        FxcBookRequestMessageSet set = new FxcBookRequestMessageSet();
        set.setBookRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID("BK-1");
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(set);
        env.setMessageSets(sets);

        RequestEnvelope parsed = OfxCodec.unmarshalRequest(
                new ByteArrayInputStream(OfxCodec.marshalRequest(env)));
        FxcBookRequestMessageSet parsedSet = parsed.getMessageSets().stream()
                .filter(s -> s instanceof FxcBookRequestMessageSet).map(s -> (FxcBookRequestMessageSet) s)
                .findFirst().orElseThrow();
        FxcBookRequest parsedBody = parsedSet.getBookRequest().getMessage();
        assertEquals("ACME", parsedBody.getSecurityId().getUniqueId());
        assertEquals(5, parsedBody.getDepth());
    }

    @Test
    void bookResponseWithLevelsRoundTrips() throws Exception {
        SecurityId secId = new SecurityId();
        secId.setUniqueId("ACME");
        secId.setUniqueIdType("TICKER");

        FxcBookResponse body = new FxcBookResponse();
        body.setSecurityId(secId);
        body.setLastPrice(42.10);
        body.setLevels(List.of(
                new FxcBookLevel("BID", 42.09, 300.0),
                new FxcBookLevel("BID", 42.08, 500.0),
                new FxcBookLevel("OFFER", 42.11, 200.0)));

        FxcBookResponseTransaction txn = new FxcBookResponseTransaction();
        txn.setUID("BK-1");
        Status status = new Status();
        status.setCode(Status.KnownCode.SUCCESS);
        status.setSeverity(Status.Severity.INFO);
        txn.setStatus(status);
        txn.setMessage(body);
        FxcBookResponseMessageSet set = new FxcBookResponseMessageSet();
        set.setBookResponse(txn);

        ResponseEnvelope env = new ResponseEnvelope();
        env.setUID("BK-1");
        TreeSet<ResponseMessageSet> sets = new TreeSet<>();
        sets.add(set);
        env.setMessageSets(sets);

        ResponseEnvelope parsed = OfxCodec.unmarshalResponse(
                new ByteArrayInputStream(OfxCodec.marshal(env)));
        FxcBookResponseMessageSet parsedSet = parsed.getMessageSets().stream()
                .filter(s -> s instanceof FxcBookResponseMessageSet).map(s -> (FxcBookResponseMessageSet) s)
                .findFirst().orElseThrow();
        FxcBookResponse parsedBody = parsedSet.getBookResponse().getMessage();
        assertNotNull(parsedBody);
        assertEquals("ACME", parsedBody.getSecurityId().getUniqueId());
        assertEquals(42.10, parsedBody.getLastPrice(), 1e-9);
        assertEquals(3, parsedBody.getLevels().size(), "all three book levels should survive");
        assertEquals("BID", parsedBody.getLevels().get(0).getSide());
        assertEquals(42.09, parsedBody.getLevels().get(0).getPrice(), 1e-9);
        assertEquals(300.0, parsedBody.getLevels().get(0).getSize(), 1e-9);
    }
}
