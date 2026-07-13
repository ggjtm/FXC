package com.fxc.common.ofx;

import com.webcohesion.ofx4j.domain.data.RequestEnvelope;
import com.webcohesion.ofx4j.domain.data.ResponseEnvelope;
import com.webcohesion.ofx4j.io.AggregateMarshaller;
import com.webcohesion.ofx4j.io.AggregateUnmarshaller;
import com.webcohesion.ofx4j.io.OFXParseException;
import com.webcohesion.ofx4j.io.v2.OFXV2Writer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * OFX 2.x marshalling helpers used by both the broker's HTTP handler and OFX clients. Bypasses the
 * Jakarta {@code OFXServlet} (PROBLEMS.md B1) and drives {@link AggregateMarshaller} /
 * {@link AggregateUnmarshaller} directly.
 *
 * <p><b>Important:</b> {@link OFXV2Writer} buffers and only flushes its trailing close-tags on
 * {@code close()} — marshalling without closing yields truncated XML. This helper always closes.
 */
public final class OfxCodec {

    public static final String CONTENT_TYPE = "application/x-ofx";

    private OfxCodec() {
    }

    /** Marshal a response envelope to OFX 2.x XML bytes. */
    public static byte[] marshal(ResponseEnvelope response) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            OFXV2Writer writer = new OFXV2Writer(out);
            new AggregateMarshaller().marshal(response, writer);
            writer.close(); // flushes buffered closing tags
        } catch (IOException e) {
            throw new IllegalStateException("Failed to marshal OFX response", e);
        }
        return out.toByteArray();
    }

    /** Marshal a request envelope to OFX 2.x XML bytes (client side / tests). */
    public static byte[] marshalRequest(RequestEnvelope request) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            OFXV2Writer writer = new OFXV2Writer(out);
            new AggregateMarshaller().marshal(request, writer);
            writer.close();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to marshal OFX request", e);
        }
        return out.toByteArray();
    }

    public static RequestEnvelope unmarshalRequest(InputStream in) throws IOException, OFXParseException {
        return new AggregateUnmarshaller<>(RequestEnvelope.class).unmarshal(in);
    }

    public static ResponseEnvelope unmarshalResponse(InputStream in) throws IOException, OFXParseException {
        return new AggregateUnmarshaller<>(ResponseEnvelope.class).unmarshal(in);
    }

    public static String toString(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8);
    }
}
