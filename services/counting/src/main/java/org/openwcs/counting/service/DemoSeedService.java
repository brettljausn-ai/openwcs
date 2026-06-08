package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.openwcs.counting.api.DemoSeedResult;
import org.openwcs.counting.client.InventoryClient;
import org.springframework.stereotype.Service;

/**
 * Demo "add count tasks" seeding (build.md §4.8): bulk-creates a handful of sample count tasks over
 * real demo stock so the stock-counting screen can be demoed with one click. Cells are sourced from
 * the inventory stock projection (so each line snapshots a meaningful expected qty); requires demo
 * stock to exist and is a no-op-with-error otherwise.
 */
@Service
public class DemoSeedService {

    private final CountingService counting;
    private final InventoryClient inventory;
    private final Random rnd = new Random();

    public DemoSeedService(CountingService counting, InventoryClient inventory) {
        this.counting = counting;
        this.inventory = inventory;
    }

    /**
     * Create {@code count} demo count tasks for a warehouse from existing demo stock.
     *
     * @throws IllegalStateException if there is no stock to count (demo mode not enabled / seeded)
     */
    public DemoSeedResult seed(UUID warehouseId, int count) {
        int n = count <= 0 ? 10 : Math.min(count, 100);

        List<InventoryClient.StockCell> stock = inventory.listStockCells(warehouseId);
        if (stock.isEmpty()) {
            throw new IllegalStateException("No demo stock to count (enable demo mode, which seeds stock, first).");
        }

        int created = 0;
        for (int i = 0; i < n; i++) {
            counting.generate(buildTask(warehouseId, stock));
            created++;
        }
        return new DemoSeedResult(created);
    }

    private CreateCountTaskCommand buildTask(UUID warehouseId, List<InventoryClient.StockCell> stock) {
        int cellCount = 1 + rnd.nextInt(3);
        Set<InventoryClient.StockCell> chosen = new LinkedHashSet<>();
        while (chosen.size() < cellCount && chosen.size() < stock.size()) {
            chosen.add(stock.get(rnd.nextInt(stock.size())));
        }
        List<CountTaskScope> cells = new ArrayList<>();
        for (InventoryClient.StockCell c : chosen) {
            cells.add(new CountTaskScope(c.locationId(), c.skuId(), null, null));
        }
        return new CreateCountTaskCommand(warehouseId, "LOCATION", null, "BLIND", "AD_HOC",
                null, null, BigDecimal.ONE, null, cells);
    }
}
