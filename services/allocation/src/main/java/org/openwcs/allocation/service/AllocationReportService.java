package org.openwcs.allocation.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.openwcs.allocation.api.StockBlockingReport;
import org.openwcs.allocation.client.InventoryClient;
import org.openwcs.allocation.domain.AllocationLine;
import org.openwcs.allocation.domain.OrderAllocation;
import org.openwcs.allocation.repo.OrderAllocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock-blocking aggregate for the dashboard / alerting (read-only, best-effort).
 *
 * <p>A <b>blocked line</b> is an outbound order line currently unfulfillable from pickable
 * stock: an {@link AllocationLine} in {@code SHORT} status on a non-cancelled order. Each
 * blocked line is split into:
 *
 * <ul>
 *   <li><b>recoverable</b> — the SKU still has warehouse-wide available-to-promise somewhere
 *       (reserve / ASRS that just was not at a pick face), so a replenishment can clear it,</li>
 *   <li><b>zeroStock</b> — the SKU has no available stock anywhere; only a receipt clears it.</li>
 * </ul>
 *
 * The split queries inventory once per distinct short SKU. When inventory is unreachable the
 * split degrades safely to {@code recoverable = 0} and {@code zeroStock = blockedLines} (we
 * cannot prove stock exists, so nothing is reported as recoverable).
 */
@Service
public class AllocationReportService {

    private static final Logger log = LoggerFactory.getLogger(AllocationReportService.class);

    private final OrderAllocationRepository allocations;
    private final InventoryClient inventory;

    public AllocationReportService(OrderAllocationRepository allocations, InventoryClient inventory) {
        this.allocations = allocations;
        this.inventory = inventory;
    }

    @Transactional(readOnly = true)
    public StockBlockingReport stockBlocking(UUID warehouseId) {
        List<OrderAllocation> open = allocations.findOpenWithLinesByWarehouseId(warehouseId);

        long openOutboundLines = 0;
        long blockedLines = 0;
        Set<UUID> shortSkus = new HashSet<>();
        for (OrderAllocation order : open) {
            for (AllocationLine line : order.getLines()) {
                openOutboundLines++;
                if ("SHORT".equals(line.getStatus())) {
                    blockedLines++;
                    shortSkus.add(line.getSkuId());
                }
            }
        }

        // Best-effort recoverable/zero split: stock somewhere (ATP > 0) => recoverable.
        Map<UUID, Boolean> hasStock = new HashMap<>();
        boolean splitAvailable = true;
        for (UUID sku : shortSkus) {
            try {
                BigDecimal atp = inventory.available(warehouseId, sku);
                hasStock.put(sku, atp != null && atp.signum() > 0);
            } catch (RuntimeException e) {
                log.warn("stock-blocking recoverable/zero split degraded for warehouse {} because"
                        + " inventory is unreachable ({}); reporting all blocked lines as zero-stock",
                        warehouseId, e.toString());
                splitAvailable = false;
                break;
            }
        }

        long recoverableLines = 0;
        long zeroStockLines = 0;
        if (splitAvailable) {
            for (OrderAllocation order : open) {
                for (AllocationLine line : order.getLines()) {
                    if ("SHORT".equals(line.getStatus())) {
                        if (hasStock.getOrDefault(line.getSkuId(), false)) {
                            recoverableLines++;
                        } else {
                            zeroStockLines++;
                        }
                    }
                }
            }
        } else {
            zeroStockLines = blockedLines;
        }

        log.debug("stock-blocking report for warehouse {}: {} blocked of {} open lines, {} distinct"
                        + " short SKUs ({} recoverable / {} zero-stock, split {})",
                warehouseId, blockedLines, openOutboundLines, shortSkus.size(),
                recoverableLines, zeroStockLines, splitAvailable ? "applied" : "degraded");

        return new StockBlockingReport(
                blockedLines, shortSkus.size(), recoverableLines, zeroStockLines, openOutboundLines);
    }
}
