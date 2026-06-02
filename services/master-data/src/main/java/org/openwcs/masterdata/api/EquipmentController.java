package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.Equipment;
import org.openwcs.masterdata.repo.EquipmentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Equipment registry CRUD (build.md §6). */
@RestController
@RequestMapping("/api/master-data/equipment")
public class EquipmentController {

    private final EquipmentRepository equipment;

    public EquipmentController(EquipmentRepository equipment) {
        this.equipment = equipment;
    }

    @GetMapping
    public List<Equipment> list(@RequestParam UUID warehouseId, @RequestParam(required = false) String family) {
        return family != null
                ? equipment.findByWarehouseIdAndFamily(warehouseId, family)
                : equipment.findByWarehouseId(warehouseId);
    }

    @PostMapping
    public ResponseEntity<Equipment> create(@RequestBody Equipment body) {
        body.setId(null);
        Equipment saved = equipment.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/equipment/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public Equipment get(@PathVariable UUID id) {
        return equipment.findById(id).orElseThrow(() -> new NotFoundException("Equipment", id));
    }

    @PutMapping("/{id}")
    public Equipment update(@PathVariable UUID id, @RequestBody Equipment body) {
        Equipment existing = equipment.findById(id).orElseThrow(() -> new NotFoundException("Equipment", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return equipment.save(body);
    }
}
