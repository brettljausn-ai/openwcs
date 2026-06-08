package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.counting.client.FlowClient;
import org.openwcs.counting.client.GtpClient;
import org.openwcs.counting.client.InventoryClient;
import org.openwcs.counting.client.MasterDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes count cells whose stock lives in an ASRS-family storage block to a goods-to-person counting
 * station. When the hardware emulator is ON and an ACTIVE station accepts {@code STOCK_COUNT} work,
 * each qualifying cell's handling unit (tote) gets a flow transport device task (so the move shows up
 * on the Transport screen) and is enqueued to the station in {@code STOCK_COUNT} mode.
 *
 * <p>Best-effort: any failure (emulator off, no station, not ASRS, no HU, a service being down) is
 * logged and skipped. Routing never breaks count-task creation.
 */
@Service
public class CountRoutingService {

    private static final Logger log = LoggerFactory.getLogger(CountRoutingService.class);
    private static final String MODE = "STOCK_COUNT";
    private static final String FAMILY = "ASRS";
    private static final String COMMAND = "RETRIEVE";

    private final MasterDataClient masterData;
    private final InventoryClient inventory;
    private final GtpClient gtp;
    private final FlowClient flow;

    public CountRoutingService(MasterDataClient masterData, InventoryClient inventory,
                               GtpClient gtp, FlowClient flow) {
        this.masterData = masterData;
        this.inventory = inventory;
        this.gtp = gtp;
        this.flow = flow;
    }

    /**
     * Route every ASRS-stored cell to an active counting station. No-op when the emulator is off or
     * no station can take stock-count work. Each cell is wrapped in its own try/catch so one bad cell
     * never blocks the rest.
     */
    public void routeAsrsCells(UUID warehouseId, List<CountTaskScope> cells) {
        if (warehouseId == null || cells == null || cells.isEmpty()) {
            return;
        }
        if (!masterData.emulatorEnabled()) {
            return; // hardware emulator OFF: leave count totes where they are.
        }
        Optional<UUID> station = gtp.findActiveCountingStation(warehouseId);
        if (station.isEmpty()) {
            log.debug("no active STOCK_COUNT station in warehouse {}; skipping ASRS count routing", warehouseId);
            return;
        }
        UUID stationId = station.get();

        for (CountTaskScope cell : cells) {
            try {
                routeCell(warehouseId, stationId, cell);
            } catch (RuntimeException e) {
                log.warn("could not route count cell (location {}, sku {}) to station {}: {}",
                        cell.locationId(), cell.skuId(), stationId, e.toString());
            }
        }
    }

    private void routeCell(UUID warehouseId, UUID stationId, CountTaskScope cell) {
        String storageType = masterData.storageTypeOfLocation(warehouseId, cell.locationId()).orElse(null);
        if (!MasterDataClient.isAsrsFamily(storageType)) {
            return; // not an automated system; the operator counts it in place.
        }
        Optional<InventoryClient.HandlingUnit> hu =
                inventory.findHuAt(warehouseId, cell.skuId(), cell.locationId());
        if (hu.isEmpty()) {
            return; // no tote to move.
        }
        InventoryClient.HandlingUnit tote = hu.get();
        String skuCode = masterData.skuCode(cell.skuId()).orElse(null);

        // Transport the tote so the retrieval is visible on the Transport screen.
        Map<String, Object> payload = new HashMap<>();
        payload.put("destinationStationId", stationId);
        payload.put("huId", tote.huId());
        payload.put("huCode", tote.huCode());
        payload.put("skuId", cell.skuId());
        payload.put("skuCode", skuCode);
        payload.put("locationId", cell.locationId());
        payload.put("reason", MODE);
        UUID transportId = flow.createTransport(warehouseId, FAMILY, COMMAND, payload, tote.huId());

        // Pin the tote to the station's stock-count queue (immediate: distanceM null).
        BigDecimal qty = tote.qty();
        gtp.enqueue(stationId, new GtpClient.EnqueueRequest(
                tote.huId(), tote.huCode(), cell.skuId(), skuCode, qty, MODE, FAMILY, null));

        log.info("routed count tote {} (sku {}) to GTP station {} for stock count; transport {}",
                tote.huCode(), skuCode, stationId, transportId);
    }
}
