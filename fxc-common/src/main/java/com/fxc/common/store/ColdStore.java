package com.fxc.common.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic MariaDB cold store (docs/DESIGN.md §3.2, root PLAN Phase 5): a small HikariCP pool plus
 * schema application, shared by the components' archival services. Public API is {@code java.sql}
 * only — callers run their own inserts/queries via {@link #connection()}.
 */
public final class ColdStore implements AutoCloseable {

    private final HikariDataSource dataSource;

    private ColdStore(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Open a pool. */
    public static ColdStore open(String jdbcUrl, String user, String password, String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setPoolName(poolName);
        return new ColdStore(new HikariDataSource(config));
    }

    /** Open a pool and apply a schema from a classpath resource. */
    public static ColdStore open(String jdbcUrl, String user, String password, String poolName,
                                 String schemaResource) {
        ColdStore store = open(jdbcUrl, user, password, poolName);
        store.applySchema(schemaResource);
        return store;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Apply a SQL schema resource (statements split on {@code ;}, {@code --} comments stripped). */
    public void applySchema(String schemaResource) {
        String schema = readResource(schemaResource);
        try (Connection conn = connection(); Statement stmt = conn.createStatement()) {
            for (String statement : splitStatements(schema)) {
                stmt.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply schema " + schemaResource, e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static String readResource(String resource) {
        try (InputStream in = ColdStore.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + resource, e);
        }
    }

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
