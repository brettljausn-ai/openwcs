package org.openwcs.inventory.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.inventory.api.InventoryDashboard;
import org.openwcs.inventory.api.StockBySkuRow;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.repo.HandlingUnitRepository;
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
    private final HandlingUnitRepository handlingUnits;
    private final StorageDensityService density;
    private final MasterDataClient masterData;

    public InventoryReportService(
            StockRepository stock,
            ReservationRepository reservations,
            HandlingUnitRepository handlingUnits,
            StorageDensityService density,
            MasterDataClient masterData) {
        this.stock = stock;
        this.reservations = reservations;
        this.handlingUnits = handlingUnits;
        this.density = density;
        this.masterData = masterData;
    }

    /**
     * Dashboard KPIs for one warehouse (best-effort: utilisation and backlog age degrade to
     * null when a dependency is unreachable, the rest always answers from local tables).
     *
     * <ul>
     *   <li><b>husReceivedToday / huCount</b>: HUs created today / total, from the HU table,</li>
     *   <li><b>skuCountWithStock</b>: distinct SKUs with any stock row,</li>
     *   <li><b>utilisationPct / asrsUtilisationPct</b>: today's storage fill, reusing the
     *       {@link StorageDensityService} occupied/total-cell computation (overall + ASRS),</li>
     *   <li><b>putawayBacklog</b>: HUs parked at the warehouse's RECEIVING / UNKNOWN locations
     *       (received, not yet stored), with the oldest one's age in minutes; count falls back
     *       to 0 and the age to null when master-data cannot resolve those locations.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public InventoryDashboard dashboard(UUID warehouseId) {
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long husReceivedToday =
                handlingUnits.countByWarehouseIdAndCreatedAtGreaterThanEqual(warehouseId, startOfToday);
        long huCount = handlingUnits.countByWarehouseId(warehouseId);
        long skuCountWithStock = stock.countDistinctSkusWithStock(warehouseId);

        StorageDensityService.Utilisation util = density.todayUtilisation(warehouseId);
        InventoryDashboard.PutawayBacklog backlog = putawayBacklog(warehouseId);

        return new InventoryDashboard(
                husReceivedToday, huCount, skuCountWithStock,
                util.overallPct(), util.asrsPct(), backlog);
    }

    /** HUs at RECEIVING + UNKNOWN locations, oldest-first; count + oldest age (best-effort). */
    private InventoryDashboard.PutawayBacklog putawayBacklog(UUID warehouseId) {
        List<UUID> locations = new ArrayList<>();
        try {
            locations.addAll(masterData.receivingLocationIds(warehouseId));
            locations.add(masterData.unknownLocationId(warehouseId));
        } catch (MasterDataUnavailableException e) {
            log.warn("put-away backlog skipped for warehouse {} because master-data is unreachable"
                    + " ({}); reporting count 0 and null age", warehouseId, e.getMessage());
            return new InventoryDashboard.PutawayBacklog(0, null);
        }
        if (locations.isEmpty()) {
            return new InventoryDashboard.PutawayBacklog(0, null);
        }
        List<HandlingUnit> backlog =
                handlingUnits.findBacklogByWarehouseIdAndLocationIdIn(warehouseId, locations);
        Long oldestAgeMin = backlog.isEmpty() ? null
                : Duration.between(backlog.get(0).getCreatedAt(), Instant.now()).toMinutes();
        return new InventoryDashboard.PutawayBacklog(backlog.size(), oldestAgeMin);
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
