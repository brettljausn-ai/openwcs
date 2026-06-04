package org.openwcs.slotting.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.StorageProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Storage-profile teach-in CRUD — assigns SKUs to blocks with velocity/redundancy knobs. */
@RestController
@RequestMapping("/api/slotting/storage-profiles")
public class StorageProfileController {

    private final StorageProfileRepository profiles;

    public StorageProfileController(StorageProfileRepository profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public List<StorageProfile> list(@RequestParam UUID warehouseId,
                                     @RequestParam(required = false) UUID skuId) {
        return skuId != null
                ? profiles.findByWarehouseIdAndSkuId(warehouseId, skuId)
                : profiles.findByWarehouseId(warehouseId);
    }

    @PostMapping
    public ResponseEntity<StorageProfile> create(@RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
                                                 @RequestBody StorageProfile body) {
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(null);
        StorageProfile saved = profiles.save(body);
        return ResponseEntity.created(URI.create("/api/slotting/storage-profiles/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public StorageProfile get(@PathVariable UUID id) {
        return profiles.findById(id).orElseThrow(() -> new NotFoundException("StorageProfile", id));
    }

    @PutMapping("/{id}")
    public StorageProfile update(@PathVariable UUID id,
                                 @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
                                 @RequestBody StorageProfile body) {
        StorageProfile existing = profiles.findById(id).orElseThrow(() -> new NotFoundException("StorageProfile", id));
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(id);
        body.setVersion(existing.getVersion());
        return profiles.save(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        profiles.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
