package org.openwcs.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.service.HandlingUnitNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Handling-unit registry: the physical units (barcode + type + location) that hold stock. */
@RestController
@RequestMapping("/api/inventory/handling-units")
public class HandlingUnitController {

    private final HandlingUnitRepository handlingUnits;

    public HandlingUnitController(HandlingUnitRepository handlingUnits) {
        this.handlingUnits = handlingUnits;
    }

    /** All handling units registered in a warehouse. */
    @GetMapping
    public List<HandlingUnit> list(@RequestParam UUID warehouseId) {
        return handlingUnits.findByWarehouseId(warehouseId);
    }

    /** Count of ACTIVE handling units of a given type (across warehouses) — guards HU-type archiving. */
    @GetMapping("/active-count")
    public long activeCount(@RequestParam UUID huTypeId) {
        return handlingUnits.countByHuTypeIdAndStatus(huTypeId, "ACTIVE");
    }

    @GetMapping("/{id}")
    public HandlingUnit get(@PathVariable UUID id) {
        return handlingUnits.findById(id)
                .orElseThrow(() -> new HandlingUnitNotFoundException(id));
    }

    @PostMapping
    public ResponseEntity<HandlingUnit> create(@RequestBody HandlingUnit handlingUnit) {
        handlingUnit.setHuId(null);
        HandlingUnit saved = handlingUnits.save(handlingUnit);
        return ResponseEntity
                .created(URI.create("/api/inventory/handling-units/" + saved.getHuId()))
                .body(saved);
    }

    @PutMapping("/{id}")
    public HandlingUnit update(@PathVariable UUID id, @RequestBody HandlingUnit handlingUnit) {
        if (!handlingUnits.existsById(id)) {
            throw new HandlingUnitNotFoundException(id);
        }
        handlingUnit.setHuId(id);
        return handlingUnits.save(handlingUnit);
    }
}
