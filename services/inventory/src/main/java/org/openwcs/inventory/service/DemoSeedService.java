package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.openwcs.inventory.api.DemoClearResult;
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

    /** Demo HU codes share this prefix so {@link #clear(UUID)} can recognise and remove them. */
    private static final String DEMO_HU_PREFIX = "DEMO-HU-";
    /** Empty handling units seeded alongside the stocked ones (empty-HU management, GTP order totes). */
    private static final int EMPTY_HU_COUNT = 50;
    private static final long SEED = 20260604L;

    private final HandlingUnitRepository handlingUnits;
    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final SerialUnitRepository serialUnits;
    private final BatchRepository batches;

    public DemoSeedService(
            HandlingUnitRepository handlingUnits,
            StockRepository stock,
            ReservationRepository reservations,
            SerialUnitRepository serialUnits,
            BatchRepository batches) {
        this.handlingUnits = handlingUnits;
        this.stock = stock;
        this.reservations = reservations;
        this.serialUnits = serialUnits;
        this.batches = batches;
    }

    @Transactional
    public DemoSeedResult seed(DemoSeedRequest req) {
        List<UUID> locationIds = req.locationIds();
        List<UUID> skuIds = req.skuIds();
        if (locationIds == null || locationIds.isEmpty() || skuIds == null || skuIds.isEmpty()) {
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

        return new DemoSeedResult(huCreated + emptyCreated, emptyCreated, stockCreated);
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

        return new DemoClearResult(reservationRows, stockRows, hus, serials, batchRows);
    }
}
