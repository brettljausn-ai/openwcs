package org.openwcs.integration.host.api;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.openwcs.integration.host.client.TxLogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Host-driven inventory adjustments on the canonical Host API. */
@RestController
@RequestMapping("/api/host/inventory")
public class HostInventoryController {

    private static final Logger log = LoggerFactory.getLogger(HostInventoryController.class);

    private final TxLogClient txLog;

    public HostInventoryController(TxLogClient txLog) {
        this.txLog = txLog;
    }

    @PostMapping("/adjustments")
    public TxLogClient.Appended adjust(
            @Valid @RequestBody InventoryAdjustment adjustment,
            @RequestHeader(value = "X-Auth-User", required = false) String actor) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("warehouseId", adjustment.warehouseId());
        payload.put("skuId", adjustment.skuId());
        payload.put("locationId", adjustment.locationId());
        payload.put("qtyDelta", adjustment.qtyDelta());
        payload.put("uomCode", adjustment.uomCode());
        payload.put("status", adjustment.status());
        payload.put("reason", adjustment.reason());
        // Stream the adjustment as a StockAdjusted event keyed on the SKU; the inventory
        // projection applies the signed delta.
        TxLogClient.Appended appended = txLog.append(adjustment.skuId().toString(), "StockAdjusted",
                actor == null ? "host" : actor, payload);
        log.info("host inventory adjustment accepted: sku {} at location {} delta {} {} (reason: {}, by {}) -> StockAdjusted at tx-log position {}",
                adjustment.skuId(), adjustment.locationId(), adjustment.qtyDelta(), adjustment.uomCode(),
                adjustment.reason(), actor == null ? "host" : actor, appended.position());
        return appended;
    }
}
