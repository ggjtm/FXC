package com.fxc.pub.service;

import com.fxc.pub.grid.PubRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maintains the timeline projection (docs/DESIGN.md §4.3): records statuses into the GridGain
 * {@code STATUS} hot table and serves recent-feed reads. Fed by the pubsub events FxcPub's
 * XMPP-client services observe (here, driven directly by {@link FixGatewayService} on publish).
 */
public final class TimelineService {

    private final PubRepository repository;
    private final AtomicLong sequence = new AtomicLong();

    public TimelineService(PubRepository repository) {
        this.repository = repository;
    }

    /** Record a status on a feed and return it. */
    public StatusRecord record(String feed, String author, String body, long createdAt) {
        long seq = sequence.incrementAndGet();
        StatusRecord status = new StatusRecord(feed + "-" + seq, feed, author, body, createdAt, seq);
        repository.insertStatus(status);
        return status;
    }

    /** Most recent statuses on a feed, newest first. */
    public List<StatusRecord> recent(String feed, int limit) {
        return repository.recentStatuses(feed, limit);
    }
}
