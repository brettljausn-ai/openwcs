package org.openwcs.integration.host.api;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.openwcs.integration.host.client.TxLogClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Host-driven inventory adjustments on the canonical Host API. */
@RestController
@RequestMapping("/api/host/inventory")
public class HostInventoryController {

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
        return txLog.append(adjustment.skuId().toString(), "StockAdjusted",
                actor == null ? "host" : actor, payload);
    }
}
