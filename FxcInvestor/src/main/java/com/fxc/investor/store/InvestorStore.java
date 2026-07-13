package com.fxc.investor.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * FxcInvestor's MariaDB primary store (docs/DESIGN.md §4.4), via plain JDBC over a HikariCP pool.
 * Applies {@code db/schema.sql} on open and persists the decision log. Best-effort at the call
 * sites: the runner uses it if the DB is reachable and otherwise runs without persistence.
 */
public final class InvestorStore implements AutoCloseable {

    private static final String SCHEMA_RESOURCE = "db/schema.sql";

    private final HikariDataSource dataSource;

    private InvestorStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Open the store, creating a small connection pool and applying the schema. */
    public static InvestorStore open(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setPoolName("fxc-investor");
        HikariDataSource ds = new HikariDataSource(config);
        InvestorStore store = new InvestorStore(ds);
        store.applySchema();
        return store;
    }

    private void applySchema() {
        String schema = readResource();
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(schema)) {
                stmt.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply FxcInvestor schema", e);
        }
    }

    /** Append a decision to the log. */
    public void logDecision(DecisionRecord d) {
        String sql = "INSERT INTO DECISION_LOG "
                + "(created_at, account, symbol, strategy, side, quantity, price, cl_ord_id, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, d.createdAt());
            ps.setString(2, d.account());
            ps.setString(3, d.symbol());
            ps.setString(4, d.strategy());
            ps.setString(5, d.side());
            setDecimal(ps, 6, d.quantity());
            setDecimal(ps, 7, d.price());
            ps.setString(8, d.clOrdId());
            ps.setString(9, d.status());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to log decision", e);
        }
    }

    /** Most recent decisions for an account, newest first. */
    public List<DecisionRecord> recent(String account, int limit) {
        String sql = "SELECT created_at, account, symbol, strategy, side, quantity, price, cl_ord_id, status "
                + "FROM DECISION_LOG WHERE account = ? ORDER BY id DESC LIMIT ?";
        List<DecisionRecord> out = new ArrayList<>();
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, account);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DecisionRecord(
                            rs.getLong("created_at"), rs.getString("account"), rs.getString("symbol"),
                            rs.getString("strategy"), rs.getString("side"), rs.getBigDecimal("quantity"),
                            rs.getBigDecimal("price"), rs.getString("cl_ord_id"), rs.getString("status")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read decision log", e);
        }
        return out;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static void setDecimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.DECIMAL);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static String readResource() {
        try (InputStream in = InvestorStore.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found: " + SCHEMA_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + SCHEMA_RESOURCE, e);
        }
    }

    /** Strip {@code --} comments (line and inline), then split into statements on {@code ;}. */
    private static List<String> splitStatements(String sql) {
        // Inline comments may contain ';', so remove them before splitting on ';'.
        String noComments = sql.replaceAll("(?m)--.*$", "");
        List<String> statements = new ArrayList<>();
        for (String part : noComments.split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                statements.add(s);
            }
        }
        return statements;
    }
}
