package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.domain.Equipment;
import org.openwcs.masterdata.repo.EquipmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    public ResponseEntity<Equipment> create(
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody Equipment body) {
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(null);
        Equipment saved = equipment.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/equipment/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public Equipment get(@PathVariable UUID id) {
        return equipment.findById(id).orElseThrow(() -> new NotFoundException("Equipment", id));
    }

    @PutMapping("/{id}")
    public Equipment update(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody Equipment body) {
        Equipment existing = equipment.findById(id).orElseThrow(() -> new NotFoundException("Equipment", id));
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(id);
        body.setVersion(existing.getVersion());
        return equipment.save(body);
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
