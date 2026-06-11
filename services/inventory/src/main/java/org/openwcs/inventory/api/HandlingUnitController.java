package org.openwcs.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.springframework.transaction.annotation.Transactional;
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
    private final StockRepository stock;

    public HandlingUnitController(HandlingUnitRepository handlingUnits, StockRepository stock) {
        this.handlingUnits = handlingUnits;
        this.stock = stock;
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
        HandlingUnit existing = handlingUnits.findById(id)
                .orElseThrow(() -> new HandlingUnitNotFoundException(id));
        handlingUnit.setHuId(id);
        // The HU's type and current location are NOT editable here — they change only through a
        // controlled process (maintenance / QA). Preserve the existing values regardless of the body.
        handlingUnit.setHuTypeId(existing.getHuTypeId());
        handlingUnit.setLocationId(existing.getLocationId());
        return handlingUnits.save(handlingUnit);
    }

    /**
     * Book the HU's current location through the transport lifecycle (the controlled process the
     * full PUT above defers to): a RETRIEVE out of a slot books {@code null} (in transit / at a
     * workplace — the HU transport trace is the truth while away); the return-leg STORE books the
     * slot back.
     */
    @PutMapping("/{id}/location")
    @Transactional
    public HandlingUnit updateLocation(@PathVariable UUID id, @RequestBody LocationUpdateRequest request) {
        HandlingUnit existing = handlingUnits.findById(id)
                .orElseThrow(() -> new HandlingUnitNotFoundException(id));
        existing.setLocationId(request.locationId());
        // The stock RIDES IN the tote: its rows' location must follow the HU, or the stock overview
        // keeps pointing at the slot the tote left (observed live after retrieves and dig-out moves).
        for (Stock s : stock.findByHuId(id)) {
            s.setLocationId(request.locationId());
            stock.save(s);
        }
        return handlingUnits.save(existing);
    }
}
