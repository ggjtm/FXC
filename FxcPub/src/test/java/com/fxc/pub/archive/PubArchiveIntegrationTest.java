package com.fxc.pub.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fxc.common.store.ColdStore;
import com.fxc.pub.grid.GridNode;
import com.fxc.pub.grid.PubRepository;
import com.fxc.pub.grid.PubTables;
import com.fxc.pub.service.StatusRecord;
import com.fxc.pub.service.TimelineService;
import java.net.Socket;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5 exit criteria (root PLAN) for FxcPub: aged statuses drain from the GridGain {@code STATUS}
 * hot projection to MariaDB (queryable there), the hot table stays bounded, and deep-history
 * timeline reads fall back to the cold archive so history survives archival.
 *
 * <p>Requires the MariaDB container; skips when 127.0.0.1:3306 is closed.
 */
class PubArchiveIntegrationTest {

    private static final String URL = "jdbc:mariadb://127.0.0.1:3306/fxc_pub";
    private static final String FEED = "feed-BROKER1";

    @Test
    void archivesAgedStatusesAndFallsBackForDeepHistory(@TempDir java.nio.file.Path workDir) throws Exception {
        assumeTrue(reachable("127.0.0.1", 3306), "MariaDB not running on 127.0.0.1:3306 — skipping");

        try (ColdStore cold = ColdStore.open(URL, "fxc", "fxc", "test-pub-cold", "db/schema.sql")) {
            cleanArchive(cold);

            try (GridNode node = GridNode.start("fxc-pub-arch", 47581, workDir.toString())) {
                PubTables.createAll(node.ignite());
                PubRepository repo = new PubRepository(node.ignite());
                TimelineService timeline = new TimelineService(repo, cold);

                // Five statuses on one feed: created_at 100..500, seq 1..5. Retention window keeps
                // created_at >= 350 hot; older ones (100, 200, 300) archive.
                for (int i = 1; i <= 5; i++) {
                    repo.insertStatus(new StatusRecord(FEED + "-" + i, FEED, "svc",
                            "FILLED: BUY " + i + " ACME @ 42.0" + i, i * 100L, i));
                }

                // clock=1000, retention=650 → cutoff=350, so statuses with created_at < 350 archive.
                ArchiveService archive = new ArchiveService(node.ignite(), cold, () -> 1_000L, 650L);
                assertEquals(5, archive.hotStatusCount(), "all five statuses hot pre-archive");

                ArchiveService.ArchiveResult result = archive.archiveNow();
                assertEquals(3, result.statuses(), "statuses created before the cutoff should archive");

                // Hot table bounded: only the 2 recent statuses remain.
                assertEquals(2, archive.hotStatusCount(), "recent statuses stay hot");
                assertEquals(3, archiveRowCount(cold), "archived statuses queryable in MariaDB");

                // Hot-only read (limit fits) returns just the hot rows, newest first.
                List<StatusRecord> hotOnly = timeline.recent(FEED, 2);
                assertEquals(2, hotOnly.size());
                assertEquals(5, hotOnly.get(0).seq(), "newest first");

                // Deep-history read (limit exceeds hot) falls back to cold and rebuilds the full window.
                List<StatusRecord> deep = timeline.recent(FEED, 5);
                assertEquals(5, deep.size(), "hot + cold union restores the full timeline");
                assertEquals(5, deep.get(0).seq(), "newest first");
                assertEquals(1, deep.get(4).seq(), "oldest last, sourced from cold");
            }
        }
    }

    private static void cleanArchive(ColdStore cold) throws Exception {
        try (Connection c = cold.connection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM STATUS_ARCHIVE");
        }
    }

    private static long archiveRowCount(ColdStore cold) throws Exception {
        try (Connection c = cold.connection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM STATUS_ARCHIVE WHERE feed = '" + FEED + "'")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket s = new Socket(host, port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
