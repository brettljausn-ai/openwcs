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
 * units against existing master-data locations and a demo HU type, and fills them with sample
 * AVAILABLE stock of the seeded SKUs, so the handling-unit registry and stock overview screens
 * are populated. Reproducible (fixed RNG seed) and reversible (clear by warehouse).
 */
@Service
public class DemoSeedService {

    /** Demo HU codes share this prefix so {@link #clear(UUID)} can recognise and remove them. */
    private static final String DEMO_HU_PREFIX = "DEMO-HU-";
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
            return new DemoSeedResult(0, 0);
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

            int rows = 1 + rnd.nextInt(2); // 1 or 2 stock rows per HU
            for (int r = 0; r < rows; r++) {
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
        }
        return new DemoSeedResult(huCreated, stockCreated);
    }

    /**
     * Full operational reset for a warehouse (build.md §4.8): purge ALL transactional
     * inventory state — reservations, stock, handling units, serial units and batches —
     * while leaving infrastructure and master-data references intact. Deleting in
     * FK-safe order (reservations and stock reference handling units / batches, so they
     * go first): Reservation → Stock → HandlingUnit → SerialUnit → Batch.
     */
    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        List<Reservation> reservationRows = reservations.findByWarehouseId(warehouseId);
        reservations.deleteAll(reservationRows);

        List<Stock> stockRows = stock.findByWarehouseId(warehouseId);
        stock.deleteAll(stockRows);

        List<HandlingUnit> hus = handlingUnits.findByWarehouseId(warehouseId);
        handlingUnits.deleteAll(hus);

        List<SerialUnit> serials = serialUnits.findByWarehouseId(warehouseId);
        serialUnits.deleteAll(serials);

        List<Batch> batchRows = batches.findByWarehouseId(warehouseId);
        batches.deleteAll(batchRows);

        return new DemoClearResult(
                reservationRows.size(),
                stockRows.size(),
                hus.size(),
                serials.size(),
                batchRows.size());
    }
}
