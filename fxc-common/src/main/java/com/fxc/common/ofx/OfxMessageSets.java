package com.fxc.common.ofx;

/**
 * Constants for FXC's custom OFX private message set. OFX 2.x has no native order-entry messages,
 * so order placement rides in a private message set inside the same OFX envelope
 * (see {@code docs/DESIGN.md} §4.2 and §6.4).
 *
 * <p><b>Placeholder for Phase 0.</b> The message-set <i>shape</i> (request/response aggregates,
 * fields) is finalized during FxcBroker implementation (Phase 2). Only the identifiers are pinned
 * here so both broker and investor agree on names.
 *
 * <p><b>OFX4J constraint (DESIGN §6.4):</b> OFX4J's unmarshaller only resolves aggregate classes
 * under {@code com.webcohesion.ofx4j.*}, so any <i>inbound</i> custom aggregate must live in that
 * package namespace — otherwise the message set is marshal-only (outbound).
 */
public final class OfxMessageSets {

    /** Identifier of the custom order-entry message set. */
    public static final String ORDER_MESSAGE_SET = "FXC.ORDERMSGSRQV1";

    /** Version tag of the custom order-entry message set. */
    public static final int ORDER_MESSAGE_SET_VERSION = 1;

    private OfxMessageSets() {
    }
}
