package com.fxc.pub.service;

/**
 * A status published to a feed (docs/DESIGN.md §4.3).
 *
 * @param statusId  unique id (the pubsub item id)
 * @param feed      feed owner (e.g. a broker id)
 * @param author    publishing JID / service
 * @param body      rendered status text
 * @param createdAt epoch millis
 * @param seq       monotonic per-engine sequence
 */
public record StatusRecord(String statusId, String feed, String author, String body,
                           long createdAt, long seq) {
}
