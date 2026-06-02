package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.Shipper;
import org.openwcs.masterdata.domain.WarehouseFulfillmentConfig;
import org.openwcs.masterdata.repo.ShipperRepository;
import org.openwcs.masterdata.repo.WarehouseFulfillmentConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shipper catalog + per-warehouse fulfilment config for cubing/allocation (ADR 0002). */
@RestController
@RequestMapping("/api/master-data")
public class FulfillmentController {

    private final ShipperRepository shippers;
    private final WarehouseFulfillmentConfigRepository configs;

    public FulfillmentController(ShipperRepository shippers, WarehouseFulfillmentConfigRepository configs) {
        this.shippers = shippers;
        this.configs = configs;
    }

    // ------------------------------------------------------------------ Shippers
    @GetMapping("/shippers")
    public List<Shipper> listShippers(@RequestParam UUID warehouseId) {
        return shippers.findByWarehouseId(warehouseId);
    }

    @PostMapping("/shippers")
    public ResponseEntity<Shipper> createShipper(@RequestBody Shipper body) {
        body.setId(null);
        Shipper saved = shippers.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/shippers/" + saved.getId())).body(saved);
    }

    @GetMapping("/shippers/{id}")
    public Shipper getShipper(@PathVariable UUID id) {
        return shippers.findById(id).orElseThrow(() -> new NotFoundException("Shipper", id));
    }

    @PutMapping("/shippers/{id}")
    public Shipper updateShipper(@PathVariable UUID id, @RequestBody Shipper body) {
        Shipper existing = shippers.findById(id).orElseThrow(() -> new NotFoundException("Shipper", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return shippers.save(body);
    }

    @DeleteMapping("/shippers/{id}")
    public ResponseEntity<Void> deleteShipper(@PathVariable UUID id) {
        Shipper existing = shippers.findById(id).orElseThrow(() -> new NotFoundException("Shipper", id));
        existing.setStatus("ARCHIVED");
        shippers.save(existing);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------- WarehouseFulfillmentConfig
    @GetMapping("/warehouses/{warehouseId}/fulfillment-config")
    public WarehouseFulfillmentConfig getConfig(@PathVariable UUID warehouseId) {
        return configs.findByWarehouseId(warehouseId)
                .orElseThrow(() -> new NotFoundException("WarehouseFulfillmentConfig for warehouse", warehouseId));
    }

    @PutMapping("/warehouses/{warehouseId}/fulfillment-config")
    public WarehouseFulfillmentConfig upsertConfig(
            @PathVariable UUID warehouseId, @RequestBody WarehouseFulfillmentConfig body) {
        body.setWarehouseId(warehouseId);
        configs.findByWarehouseId(warehouseId).ifPresentOrElse(
                existing -> {
                    body.setId(existing.getId());
                    body.setVersion(existing.getVersion());
                },
                () -> body.setId(null));
        return configs.save(body);
    }
}
