package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.inventory.api.StockBySkuRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.repo.ReservationRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock-by-SKU report for the Reporting screen (spec: "Stock per SKU in single qty, split
 * between available, allocated, unavailable"). Pure aggregate queries over the live stock
 * and reservation tables, merged per SKU:
 *
 * <ul>
 *   <li><b>allocated</b>: quantity currently HELD by reservations,</li>
 *   <li><b>available</b>: AVAILABLE-status stock minus the allocated quantity (floored at 0),
 *       excluding anything at the warehouse's UNKNOWN location,</li>
 *   <li><b>unavailable</b>: non-AVAILABLE-status stock PLUS any stock parked at the UNKNOWN
 *       location (position not known, never usable).</li>
 * </ul>
 *
 * The UNKNOWN location is resolved best-effort via master-data (same convention as
 * {@link InventoryService}); with master-data down the split still answers, only without
 * the UNKNOWN reclassification (transiently overstating availability, never understating).
 */
@Service
public class InventoryReportService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReportService.class);

    private final StockRepository stock;
    private final ReservationRepository reservations;
    private final MasterDataClient masterData;

    public InventoryReportService(
            StockRepository stock, ReservationRepository reservations, MasterDataClient masterData) {
        this.stock = stock;
        this.reservations = reservations;
        this.masterData = masterData;
    }

    @Transactional(readOnly = true)
    public List<StockBySkuRow> stockBySku(UUID warehouseId) {
        UUID unknownLocation = unknownLocationOrNull(warehouseId);
        Map<UUID, BigDecimal> available = bySku(unknownLocation == null
                ? stock.sumAvailablePerSku(warehouseId)
                : stock.sumAvailablePerSkuExcludingLocation(warehouseId, unknownLocation));
        Map<UUID, BigDecimal> unavailable = bySku(unknownLocation == null
                ? stock.sumUnavailablePerSku(warehouseId)
                : stock.sumUnavailablePerSkuIncludingLocation(warehouseId, unknownLocation));
        Map<UUID, BigDecimal> allocated = bySku(reservations.sumHeldPerSku(warehouseId));

        Set<UUID> skus = new LinkedHashSet<>();
        skus.addAll(available.keySet());
        skus.addAll(unavailable.keySet());
        skus.addAll(allocated.keySet());

        List<StockBySkuRow> rows = skus.stream()
                .sorted()
                .map(sku -> {
                    BigDecimal held = allocated.getOrDefault(sku, BigDecimal.ZERO);
                    BigDecimal usable = available.getOrDefault(sku, BigDecimal.ZERO)
                            .subtract(held).max(BigDecimal.ZERO);
                    return new StockBySkuRow(
                            sku, usable, held, unavailable.getOrDefault(sku, BigDecimal.ZERO));
                })
                .toList();
        log.debug("stock-by-sku report for warehouse {}: {} SKUs (UNKNOWN-location split {})",
                warehouseId, rows.size(), unknownLocation == null ? "skipped, master-data down" : "applied");
        return rows;
    }

    private static Map<UUID, BigDecimal> bySku(List<SkuQtyRow> rows) {
        return rows.stream().collect(Collectors.toMap(
                SkuQtyRow::skuId, SkuQtyRow::qty, BigDecimal::add, TreeMap::new));
    }

    /** Best-effort UNKNOWN-location lookup; null with master-data down (split still answers). */
    private UUID unknownLocationOrNull(UUID warehouseId) {
        try {
            return masterData.unknownLocationId(warehouseId);
        } catch (MasterDataUnavailableException e) {
            log.warn("stock-by-sku UNKNOWN-location split skipped for warehouse {} because master-data"
                    + " is unreachable ({}); stock at UNKNOWN transiently counts as available",
                    warehouseId, e.getMessage());
            return null;
        }
    }
}
