package com.fxc.broker.ofx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.RequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequest;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestMessageSet;
import com.webcohesion.ofx4j.domain.data.fxc.FxcOrderRequestTransaction;
import com.webcohesion.ofx4j.domain.data.seclist.SecurityId;
import com.webcohesion.ofx4j.io.AggregateMarshaller;
import com.webcohesion.ofx4j.io.AggregateUnmarshaller;
import com.webcohesion.ofx4j.io.v2.OFXV2Writer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Verifies the custom order-entry message set survives an OFX 2.x marshal → unmarshal round trip
 * inside a standard {@link RequestEnvelope}. This confirms the {@code com.webcohesion.ofx4j.*}
 * package placement lets OFX4J's introspector resolve our aggregates (PROBLEMS.md P4).
 */
class OfxOrderRoundTripTest {

    @Test
    void customOrderMessageSetRoundTrips() throws Exception {
        SecurityId secId = new SecurityId();
        secId.setUniqueId("FX:EURUSD");
        secId.setUniqueIdType("FXC");

        FxcOrderRequest order = new FxcOrderRequest();
        order.setAccountId("000123456");
        order.setSecurityId(secId);
        order.setSide("BUY");
        order.setUnits(1000.0);
        order.setOrderType("LIMIT");
        order.setLimitPrice(1.08420);

        FxcOrderRequestTransaction txn = new FxcOrderRequestTransaction();
        txn.setUID("ORD-1");
        txn.setWrappedMessage(order);

        FxcOrderRequestMessageSet messageSet = new FxcOrderRequestMessageSet();
        messageSet.setOrderRequest(txn);

        RequestEnvelope env = new RequestEnvelope();
        env.setUID("UID-1");
        TreeSet<RequestMessageSet> sets = new TreeSet<>();
        sets.add(messageSet);
        env.setMessageSets(sets);

        // marshal (close the writer so buffered closing tags are flushed)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OFXV2Writer writer = new OFXV2Writer(out);
        new AggregateMarshaller().marshal(env, writer);
        writer.close();
        String xml = out.toString(StandardCharsets.UTF_8);
        assertTrue(xml.contains("FXCORDMSGSRQV1"), "marshalled XML should contain the message set tag:\n" + xml);
        assertTrue(xml.contains("FX:EURUSD"), "marshalled XML should contain the SECID");

        // unmarshal
        RequestEnvelope parsed = new AggregateUnmarshaller<>(RequestEnvelope.class)
                .unmarshal(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        FxcOrderRequestMessageSet parsedSet = parsed.getMessageSets().stream()
                .filter(s -> s instanceof FxcOrderRequestMessageSet)
                .map(s -> (FxcOrderRequestMessageSet) s)
                .findFirst()
                .orElseThrow(() -> new AssertionError("custom order message set not resolved on unmarshal:\n" + xml));

        FxcOrderRequestTransaction parsedTxn = parsedSet.getOrderRequest();
        assertNotNull(parsedTxn, "order transaction");
        assertEquals("ORD-1", parsedTxn.getUID());
        FxcOrderRequest parsedOrder = parsedTxn.getMessage();
        assertNotNull(parsedOrder, "order body");
        assertEquals("000123456", parsedOrder.getAccountId());
        assertEquals("BUY", parsedOrder.getSide());
        assertEquals("LIMIT", parsedOrder.getOrderType());
        assertEquals(1000.0, parsedOrder.getUnits(), 1e-9);
        assertEquals(1.08420, parsedOrder.getLimitPrice(), 1e-9);
        assertNotNull(parsedOrder.getSecurityId());
        assertEquals("FX:EURUSD", parsedOrder.getSecurityId().getUniqueId());
    }
}
