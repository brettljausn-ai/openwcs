package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.openwcs.inventory.api.DemoSeedRequest;
import org.openwcs.inventory.api.DemoSeedResult;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
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

    public DemoSeedService(HandlingUnitRepository handlingUnits, StockRepository stock) {
        this.handlingUnits = handlingUnits;
        this.stock = stock;
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

    @Transactional
    public DemoSeedResult clear(UUID warehouseId) {
        List<HandlingUnit> demoHus = new ArrayList<>();
        Set<UUID> demoHuIds = new HashSet<>();
        for (HandlingUnit hu : handlingUnits.findByWarehouseId(warehouseId)) {
            if (hu.getCode() != null && hu.getCode().startsWith(DEMO_HU_PREFIX)) {
                demoHus.add(hu);
                demoHuIds.add(hu.getHuId());
            }
        }

        int stockRemoved = 0;
        for (Stock s : stock.findByWarehouseId(warehouseId)) {
            if (s.getHuId() != null && demoHuIds.contains(s.getHuId())) {
                stock.delete(s);
                stockRemoved++;
            }
        }

        handlingUnits.deleteAll(demoHus);
        return new DemoSeedResult(demoHus.size(), stockRemoved);
    }
}
