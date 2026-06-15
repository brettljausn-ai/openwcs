package org.openwcs.slotting.repo;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-(warehouse, sku, calendar day) pick tallies backing the exact trailing-window ABC movers
 * dashboard ({@code sku_pick_daily}, migration V6). The EWMA {@code sku_velocity} score drives
 * classification; this table records one bucket per day so any trailing window (14d, 90d, …) can
 * be summed exactly instead of approximated from the decayed score.
 *
 * <p>JDBC rather than JPA: the hot path is a single atomic upsert and the dashboard wants windowed
 * {@code SUM(picks)} aggregates grouped by SKU, neither of which fits a JPA entity cleanly.
 */
@Repository
public class SkuPickDailyRepository {

    private final JdbcTemplate jdbc;

    public SkuPickDailyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically add {@code n} picks to today's bucket for a SKU, creating it on first sight.
     * The {@code ON CONFLICT … DO UPDATE} keeps the call concurrency-safe; callers run it inside
     * the same transaction (and behind the same {@code velocity_processed_event} inbox) that folds
     * the EWMA count, so a redelivery/replay never reaches here twice and the tally never doubles.
     */
    public void addPicks(UUID warehouseId, UUID skuId, LocalDate day, long n) {
        jdbc.update(
                "INSERT INTO slotting.sku_pick_daily (warehouse_id, sku_id, day, picks) "
                        + "VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (warehouse_id, sku_id, day) "
                        + "DO UPDATE SET picks = slotting.sku_pick_daily.picks + EXCLUDED.picks",
                warehouseId, skuId, java.sql.Date.valueOf(day), n);
    }

    /**
     * Per-SKU pick counts over the trailing window {@code [from, to]} (inclusive) for one warehouse,
     * keyed by SKU id. SKUs with no picks in the window are absent (zero).
     */
    public Map<UUID, Long> windowPicksBySku(UUID warehouseId, LocalDate from, LocalDate to) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        jdbc.query(
                "SELECT sku_id, SUM(picks) AS picks FROM slotting.sku_pick_daily "
                        + "WHERE warehouse_id = ? AND day >= ? AND day <= ? GROUP BY sku_id",
                rs -> {
                    out.put(rs.getObject("sku_id", UUID.class), rs.getLong("picks"));
                },
                warehouseId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return out;
    }

    /** Total picks across all SKUs in the warehouse over the trailing window {@code [from, to]}. */
    public long windowTotal(UUID warehouseId, LocalDate from, LocalDate to) {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(picks), 0) FROM slotting.sku_pick_daily "
                        + "WHERE warehouse_id = ? AND day >= ? AND day <= ?",
                Long.class,
                warehouseId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        return total == null ? 0L : total;
    }

    /**
     * Bulk-delete every daily-pick bucket for a warehouse (demo reset). On a demo box these buckets
     * exist only because the dashboard seeder backfilled them, so clearing them empties the windowed
     * ABC movers when demo mode is turned off. Returns the row count.
     */
    public int deleteBulkByWarehouseId(UUID warehouseId) {
        return jdbc.update("DELETE FROM slotting.sku_pick_daily WHERE warehouse_id = ?", warehouseId);
    }
}
