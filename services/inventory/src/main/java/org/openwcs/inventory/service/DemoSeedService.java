package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.openwcs.inventory.api.DemoClearResult;
import org.openwcs.inventory.api.DemoDashboardSeedRequest;
import org.openwcs.inventory.api.DemoDashboardSeedResult;
import org.openwcs.inventory.api.DemoSeedRequest;
import org.openwcs.inventory.api.DemoSeedResult;
import org.openwcs.inventory.domain.Batch;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Reservation;
import org.openwcs.inventory.domain.SerialUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.BatchRepository;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.ReservationRepository;
import org.openwcs.inventory.repo.SerialUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode for the inventory service (build.md §4.8). Registers a handful of demo handling
 * units against existing master-data locations and a demo HU type, fills each with sample
 * AVAILABLE stock of exactly one SKU (the demo HU type has one compartment, and a compartment
 * holds one SKU), and adds 50 EMPTY handling units (no stock) so the
 * empty-HU flows — ASRS empty-HU management, GTP order totes — have totes to work with.
 * Reproducible (fixed RNG seed) and reversible (clear by warehouse).
 */
@Service
public class DemoSeedService {

    private static final Logger log = LoggerFactory.getLogger(DemoSeedService.class);

    /** Demo HU codes share this prefix so {@link #clear(UUID)} can recognise and remove them. */
    private static final String DEMO_HU_PREFIX = "DEMO-HU-";
    /** Empty handling units seeded alongside the stocked ones (empty-HU management, GTP order totes). */
    private static final int EMPTY_HU_COUNT = 50;
    private static final long SEED = 20260604L;

    /** Dock-to-stock HUs (received + stored today) seeded for the timing KPI. */
    private static final int DOCK_TO_STOCK_HU_COUNT = 12;
    /** HUs left parked at receiving (no stored_at) for the put-away backlog KPI. */
    private static final int BACKLOG_HU_COUNT = 3;
    private static final String DASH_HU_PREFIX = "DEMO-DASH-HU-";

    private final HandlingUnitRepository handlingUnits;
    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final SerialUnitRepository serialUnits;
    private final BatchRepository batches;
    private final JdbcTemplate jdbc;

    public DemoSeedService(
            HandlingUnitRepository handlingUnits,
            StockRepository stock,
            ReservationRepository reservations,
            SerialUnitRepository serialUnits,
            BatchRepository batches,
            JdbcTemplate jdbc) {
        this.handlingUnits = handlingUnits;
        this.stock = stock;
        this.reservations = reservations;
        this.serialUnits = serialUnits;
        this.batches = batches;
        this.jdbc = jdbc;
    }

    @Transactional
    public DemoSeedResult seed(DemoSeedRequest req) {
        List<UUID> locationIds = req.locationIds();
        List<UUID> skuIds = req.skuIds();
        if (locationIds == null || locationIds.isEmpty() || skuIds == null || skuIds.isEmpty()) {
            log.warn("demo seed skipped: no location ids or no sku ids supplied for warehouse {};"
                    + " nothing was created", req.warehouseId());
            return new DemoSeedResult(0, 0, 0);
        }

        Random rnd = new Random(SEED);
        int count = Math.min(24, Math.max(locationIds.size(), 1) * 2);
        int huCreated = 0;
        int stockCreated = 0;
        int skuCursor = 0;

        for (int k = 0; k < count; k++) {
            HandlingUnit hu = new HandlingUnit();
            hu.setCode(String.format("%s%03d", DEMO_HU_PREFIX, k));
            hu.setWarehouseId(req.warehouseId());
            hu.setHuTypeId(req.huTypeId());
            hu.setLocationId(locationIds.get(k % locationIds.size()));
            hu.setStatus("ACTIVE");
            hu = handlingUnits.save(hu);
            huCreated++;

            // One SKU per HU: the demo storage HU type has a single compartment, and one
            // compartment holds exactly one SKU — never mix SKUs into a 1-compartment tote.
            UUID skuId = skuIds.get(skuCursor % skuIds.size());
            skuCursor++;

            Stock s = new Stock();
            s.setWarehouseId(req.warehouseId());
            s.setSkuId(skuId);
            s.setLocationId(hu.getLocationId());
            s.setHuId(hu.getHuId());
            s.setStatus("AVAILABLE");
            s.setQty(BigDecimal.valueOf(5 + rnd.nextInt(46))); // 5..50
            s.setUomCode("EA");
            stock.save(s);
            stockCreated++;
        }

        // Empty handling units (no stock rows): feed the empty-HU flows — ASRS empty-HU
        // management, GTP order totes / put walls — so a demo system has totes to hand out.
        // Codes continue the DEMO-HU numbering; locations round-robin like the stocked ones.
        int emptyCreated = 0;
        for (int e = 0; e < EMPTY_HU_COUNT; e++) {
            HandlingUnit hu = new HandlingUnit();
            hu.setCode(String.format("%s%03d", DEMO_HU_PREFIX, count + e));
            hu.setWarehouseId(req.warehouseId());
            hu.setHuTypeId(req.huTypeId());
            hu.setLocationId(locationIds.get((count + e) % locationIds.size()));
            hu.setStatus("ACTIVE");
            handlingUnits.save(hu);
            emptyCreated++;
        }

        log.info("demo seed complete: {} handling units ({} stocked, {} empty, codes prefixed {}) and"
                        + " {} stock rows created in warehouse {} because demo seed was requested",
                huCreated + emptyCreated, huCreated, emptyCreated, DEMO_HU_PREFIX,
                stockCreated, req.warehouseId());
        return new DemoSeedResult(huCreated + emptyCreated, emptyCreated, stockCreated);
    }

    /**
     * Demo DASHBOARD seeding (build.md §4.8): reuses {@link #seed} for occupancy (stocked + empty
     * HUs and their stock), then adds a set of HUs received-and-stored TODAY (with a backdated
     * receive time so dock-to-stock minutes are realistic) plus a couple still parked at receiving
     * (no {@code stored_at}) for the put-away backlog. Lights /reports/dashboard's utilisation,
     * dock-to-stock and backlog figures. Strictly demo-only.
     *
     * <p>{@code created_at} is Hibernate-managed (@CreationTimestamp), so the receive time is
     * rewritten with a native UPDATE after persisting each HU.
     */
    @Transactional
    public DemoDashboardSeedResult seedDashboard(DemoDashboardSeedRequest req) {
        List<UUID> locationIds = req.locationIds();
        List<UUID> skuIds = req.skuIds();
        if (locationIds == null || locationIds.isEmpty() || skuIds == null || skuIds.isEmpty()) {
            log.warn("demo dashboard seed skipped: no location ids or sku ids for warehouse {};"
                    + " nothing was created", req.warehouseId());
            return new DemoDashboardSeedResult(0, 0, 0, 0, 0);
        }

        // 1) Reuse the existing seeder for occupancy / utilisation (stocked + empty HUs + stock).
        DemoSeedResult base = seed(new DemoSeedRequest(
                req.warehouseId(), req.huTypeId(), locationIds, skuIds));

        Random rnd = new Random(SEED);
        Instant now = Instant.now();

        // 2) Dock-to-stock HUs: received earlier today, then stored into a real storage location.
        //    created_at is backdated to the receive time; stored_at is the put-away time (today).
        int dockToStock = 0;
        for (int i = 0; i < DOCK_TO_STOCK_HU_COUNT; i++) {
            Instant received = now.minus(2L + rnd.nextInt(8), ChronoUnit.HOURS);
            Instant stored = received.plus(20L + rnd.nextInt(160), ChronoUnit.MINUTES);
            HandlingUnit hu = new HandlingUnit();
            hu.setCode(String.format("%sSTORED-%03d", DASH_HU_PREFIX, i));
            hu.setWarehouseId(req.warehouseId());
            hu.setHuTypeId(req.huTypeId());
            hu.setLocationId(locationIds.get(i % locationIds.size()));
            hu.setStatus("ACTIVE");
            hu.setStoredAt(stored);
            hu = handlingUnits.saveAndFlush(hu);
            jdbc.update("update inventory.handling_unit set created_at = ? where hu_id = ?",
                    Timestamp.from(received), hu.getHuId());

            Stock s = new Stock();
            s.setWarehouseId(req.warehouseId());
            s.setSkuId(skuIds.get(i % skuIds.size()));
            s.setLocationId(hu.getLocationId());
            s.setHuId(hu.getHuId());
            s.setStatus("AVAILABLE");
            s.setQty(BigDecimal.valueOf(5 + rnd.nextInt(46)));
            s.setUomCode("EA");
            stock.save(s);
            dockToStock++;
        }

        // 3) Put-away backlog: a couple of HUs parked at receiving (no stored_at), oldest a few
        //    hours back. Only counts as backlog when at a receiving/unknown location, so place them
        //    there when the caller supplied receiving locations (best-effort otherwise).
        List<UUID> parking = req.receivingLocationIds() != null && !req.receivingLocationIds().isEmpty()
                ? req.receivingLocationIds() : locationIds;
        int backlog = 0;
        for (int i = 0; i < BACKLOG_HU_COUNT; i++) {
            Instant received = now.minus(1L + i * 2L, ChronoUnit.HOURS);
            HandlingUnit hu = new HandlingUnit();
            hu.setCode(String.format("%sBACKLOG-%03d", DASH_HU_PREFIX, i));
            hu.setWarehouseId(req.warehouseId());
            hu.setHuTypeId(req.huTypeId());
            hu.setLocationId(parking.get(i % parking.size()));
            hu.setStatus("ACTIVE");
            // stored_at left null = still in put-away backlog.
            hu = handlingUnits.saveAndFlush(hu);
            jdbc.update("update inventory.handling_unit set created_at = ? where hu_id = ?",
                    Timestamp.from(received), hu.getHuId());
            backlog++;
        }

        log.info("demo dashboard seed for warehouse {}: occupancy {} HUs / {} stock; plus {} dock-to-stock"
                        + " HUs and {} backlog HUs because an admin requested inventory dashboard demo data",
                req.warehouseId(), base.handlingUnits(), base.stockRows(), dockToStock, backlog);
        return new DemoDashboardSeedResult(base.handlingUnits(), base.emptyHandlingUnits(),
                base.stockRows(), dockToStock, backlog);
    }

    /**
     * Full operational reset for a warehouse (build.md §4.8): purge ALL transactional
     * inventory state — reservations, stock, handling units, serial units and batches —
     * while leaving infrastructure and master-data references intact. One bulk DELETE per
     * table (a long emulator run can leave millions of rows; never load them into memory),
     * in FK-safe order: {@code batch} is the only referenced table (by reservation, stock
     * and serial_unit), so it goes last; handling units are referenced by plain uuid columns
     * only (no FK), so any order works for them.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        int reservationRows = reservations.deleteBulkByWarehouseId(warehouseId);
        int stockRows = stock.deleteBulkByWarehouseId(warehouseId);
        int serials = serialUnits.deleteBulkByWarehouseId(warehouseId);
        int hus = handlingUnits.deleteBulkByWarehouseId(warehouseId);
        int batchRows = batches.deleteBulkByWarehouseId(warehouseId);

        log.info("demo clear complete: warehouse {} operationally reset because demo mode was switched off;"
                        + " removed {} reservations, {} stock rows, {} handling units, {} serial units, {} batches",
                warehouseId, reservationRows, stockRows, hus, serials, batchRows);
        return new DemoClearResult(reservationRows, stockRows, hus, serials, batchRows);
    }
}
