package com.fxc.exchange.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Boots a real embedded GridGain/Ignite node to confirm it starts on JDK 21 with the configured
 * {@code --add-opens} flags (PROBLEMS.md P4 risk gate), and that the exchange DDL applies and is
 * queryable.
 */
class GridNodeTest {

    @Test
    void nodeBootsCreatesTablesAndRoundTripsSql(@TempDir Path workDir) {
        try (GridNode node = GridNode.start("fxc-exchange-test", 47511, workDir.toString())) {
            assertNotNull(node.ignite());

            ExchangeTables.createAll(node.ignite());

            IgniteCache<?, ?> sql = node.ignite().getOrCreateCache("fxc-sql-entry");
            sql.query(new SqlFieldsQuery(
                    "INSERT INTO INSTRUMENT (symbol, asset_class, base_ccy, quote_ccy, tick_size, lot_size, settle_style, settle_lag) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .setArgs("EUR/USD", "FX_SPOT", "EUR", "USD",
                            new java.math.BigDecimal("0.00001"), new java.math.BigDecimal("1000"),
                            "CURRENCY_EXCHANGE", 2))
                    .getAll();

            List<List<?>> rows = sql.query(
                    new SqlFieldsQuery("SELECT symbol, asset_class FROM INSTRUMENT")).getAll();

            assertEquals(1, rows.size());
            assertEquals("EUR/USD", rows.get(0).get(0));
            assertEquals("FX_SPOT", rows.get(0).get(1));
        }
    }
}
