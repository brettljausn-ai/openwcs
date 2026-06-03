package org.openwcs.slotting.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.PickSlot;
import org.openwcs.slotting.repo.PickSlotRepository;
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

/** Pick-face slotting CRUD — the slotting UI assigns SKU+UoM to a location with min/max. */
@RestController
@RequestMapping("/api/slotting/pick-slots")
public class PickSlotController {

    private final PickSlotRepository slots;

    public PickSlotController(PickSlotRepository slots) {
        this.slots = slots;
    }

    @GetMapping
    public List<PickSlot> list(@RequestParam UUID warehouseId,
                               @RequestParam(required = false) UUID skuId) {
        return skuId != null
                ? slots.findByWarehouseIdAndSkuId(warehouseId, skuId)
                : slots.findByWarehouseId(warehouseId);
    }

    @PostMapping
    public ResponseEntity<PickSlot> create(@RequestBody PickSlot body) {
        body.setId(null);
        PickSlot saved = slots.save(body);
        return ResponseEntity.created(URI.create("/api/slotting/pick-slots/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public PickSlot get(@PathVariable UUID id) {
        return slots.findById(id).orElseThrow(() -> new NotFoundException("PickSlot", id));
    }

    @PutMapping("/{id}")
    public PickSlot update(@PathVariable UUID id, @RequestBody PickSlot body) {
        PickSlot existing = slots.findById(id).orElseThrow(() -> new NotFoundException("PickSlot", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return slots.save(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        slots.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
