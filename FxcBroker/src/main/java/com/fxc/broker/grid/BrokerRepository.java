package com.fxc.broker.grid;

import com.fxc.broker.model.ClientOrder;
import com.fxc.broker.model.Execution;
import com.fxc.broker.model.HoldingType;
import com.fxc.broker.model.Position;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.SqlFieldsQuery;

/** Writes/reads FxcBroker hot state in the GridGain SQL tables ({@link BrokerTables}). */
public final class BrokerRepository {

    private final IgniteCache<?, ?> sql;

    public BrokerRepository(Ignite ignite) {
        this.sql = ignite.getOrCreateCache(BrokerTables.SQL_ENTRY_CACHE);
    }

    public void upsertAccount(String accountNumber, String ownerName, String baseCcy) {
        run("MERGE INTO ACCOUNT (account_number, owner_name, base_ccy) VALUES (?, ?, ?)",
                accountNumber, ownerName, baseCcy);
    }

    public boolean accountExists(String accountNumber) {
        return !sql.query(new SqlFieldsQuery("SELECT 1 FROM ACCOUNT WHERE account_number = ?")
                .setArgs(accountNumber)).getAll().isEmpty();
    }

    public void upsertPosition(Position p) {
        run("MERGE INTO POSITION (pos_key, account_number, holding_type, instrument, quantity, avg_price) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                p.key(), p.account(), p.holdingType().name(), p.instrument(), p.quantity(), p.avgPrice());
    }

    public List<Position> positionsForAccount(String accountNumber) {
        List<List<?>> rows = sql.query(new SqlFieldsQuery(
                "SELECT account_number, holding_type, instrument, quantity, avg_price "
                        + "FROM POSITION WHERE account_number = ?").setArgs(accountNumber)).getAll();
        List<Position> positions = new ArrayList<>();
        for (List<?> row : rows) {
            positions.add(new Position(
                    (String) row.get(0),
                    (String) row.get(2),
                    HoldingType.valueOf((String) row.get(1)),
                    (BigDecimal) row.get(3),
                    (BigDecimal) row.get(4)));
        }
        return positions;
    }

    public void upsertOrder(ClientOrder o) {
        run("MERGE INTO CLIENT_ORDER (client_order_id, account_number, symbol, side, order_type, "
                        + "limit_price, quantity, cum_qty, avg_price, status, exchange_order_id, reject_reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                o.clientOrderId(), o.account(), o.symbol(), o.side().name(), o.type().name(),
                o.limitPrice(), o.quantity(), o.cumQty(), o.avgPrice(), o.status().name(),
                o.exchangeOrderId(), o.rejectReason());
    }

    public void insertExecution(Execution e) {
        run("MERGE INTO EXECUTION (exec_id, client_order_id, symbol, side, last_qty, last_px, cum_qty, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                e.execId(), e.clientOrderId(), e.symbol(), e.side().name(),
                e.lastQty(), e.lastPx(), e.cumQty(), e.status().name());
    }

    private void run(String dml, Object... args) {
        sql.query(new SqlFieldsQuery(dml).setArgs(args)).getAll();
    }
}
