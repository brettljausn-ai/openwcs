package org.openwcs.slotting.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.slotting.domain.StorageProfile;
import org.openwcs.slotting.repo.StorageProfileRepository;
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
    public ResponseEntity<StorageProfile> create(@RequestBody StorageProfile body) {
        body.setId(null);
        StorageProfile saved = profiles.save(body);
        return ResponseEntity.created(URI.create("/api/slotting/storage-profiles/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public StorageProfile get(@PathVariable UUID id) {
        return profiles.findById(id).orElseThrow(() -> new NotFoundException("StorageProfile", id));
    }

    @PutMapping("/{id}")
    public StorageProfile update(@PathVariable UUID id, @RequestBody StorageProfile body) {
        StorageProfile existing = profiles.findById(id).orElseThrow(() -> new NotFoundException("StorageProfile", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return profiles.save(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        profiles.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
