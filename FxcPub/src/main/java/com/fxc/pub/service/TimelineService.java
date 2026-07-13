package com.fxc.pub.service;

import com.fxc.common.store.ColdStore;
import com.fxc.pub.grid.PubRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maintains the timeline projection (docs/DESIGN.md §4.3): records statuses into the GridGain
 * {@code STATUS} hot table and serves recent-feed reads. Fed by the pubsub events FxcPub's
 * XMPP-client services observe (here, driven directly by {@link FixGatewayService} on publish).
 *
 * <p>Deep-history reads: once the {@code ArchiveService} drains aged statuses from the hot table to
 * MariaDB, a {@link #recent} read that runs short in the hot table falls back to the cold
 * {@code STATUS_ARCHIVE} to fill the window (root PLAN §5). When no {@link ColdStore} is configured,
 * reads are hot-only.
 */
public final class TimelineService {

    private final PubRepository repository;
    private final ColdStore cold;
    private final AtomicLong sequence = new AtomicLong();

    public TimelineService(PubRepository repository) {
        this(repository, null);
    }

    public TimelineService(PubRepository repository, ColdStore cold) {
        this.repository = repository;
        this.cold = cold;
    }

    /** Record a status on a feed and return it. */
    public StatusRecord record(String feed, String author, String body, long createdAt) {
        long seq = sequence.incrementAndGet();
        StatusRecord status = new StatusRecord(feed + "-" + seq, feed, author, body, createdAt, seq);
        repository.insertStatus(status);
        return status;
    }

    /**
     * Most recent statuses on a feed, newest first. Reads the GridGain hot projection first; if it
     * returns fewer than {@code limit} (because older statuses have been archived), fills the
     * remainder from the MariaDB cold archive, de-duplicating by status id.
     */
    public List<StatusRecord> recent(String feed, int limit) {
        List<StatusRecord> hot = repository.recentStatuses(feed, limit);
        if (cold == null || hot.size() >= limit) {
            return hot;
        }
        // Merge hot + cold, newest (highest seq) first, dedup by status id, cap at limit.
        Map<String, StatusRecord> merged = new LinkedHashMap<>();
        for (StatusRecord s : hot) {
            merged.put(s.statusId(), s);
        }
        for (StatusRecord s : coldRecent(feed, limit)) {
            merged.putIfAbsent(s.statusId(), s);
        }
        List<StatusRecord> out = new ArrayList<>(merged.values());
        out.sort((a, b) -> Long.compare(b.seq(), a.seq()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private List<StatusRecord> coldRecent(String feed, int limit) {
        List<StatusRecord> out = new ArrayList<>();
        String sql = "SELECT status_id, feed, author, body, created_at, seq FROM STATUS_ARCHIVE "
                + "WHERE feed = ? ORDER BY seq DESC LIMIT ?";
        try (Connection c = cold.connection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, feed);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StatusRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        } catch (SQLException e) {
            // Deep-history fallback is best-effort; a cold-store hiccup must not break hot reads.
            System.err.println("Cold timeline read failed for feed " + feed + ": " + e.getMessage());
        }
        return out;
    }
}
