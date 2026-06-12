package org.openwcs.inventory.api;

import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.service.InventoryReportService;
import org.openwcs.inventory.service.StorageDensityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reporting aggregates for the Reporting screen. Read-only; like every inventory GET these
 * require INVENTORY_VIEW (enforced by {@link RbacFilter} when security is enabled).
 */
@RestController
@RequestMapping("/api/inventory/reports")
public class InventoryReportController {

    private final InventoryReportService reports;
    private final StorageDensityService density;

    public InventoryReportController(InventoryReportService reports, StorageDensityService density) {
        this.reports = reports;
        this.density = density;
    }

    /** Stock per SKU split into available / allocated / unavailable (single quantities). */
    @GetMapping("/stock-by-sku")
    public List<StockBySkuRow> stockBySku(@RequestParam UUID warehouseId) {
        return reports.stockBySku(warehouseId);
    }

    /**
     * Storage-density history: per storage block and day, occupied vs total cells and the
     * fill percentage. Today is snapshotted on demand when the daily sweep has not run yet.
     */
    @GetMapping("/storage-density")
    public List<StorageDensityRow> storageDensity(
            @RequestParam UUID warehouseId,
            @RequestParam(defaultValue = "90") int days) {
        return density.history(warehouseId, days);
    }
}
