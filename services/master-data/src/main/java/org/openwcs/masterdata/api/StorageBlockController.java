package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.masterdata.domain.StorageBlock;
import org.openwcs.masterdata.repo.StorageBlockRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Storage-block registry CRUD — slotting pools / GTP systems (ADR 0003). */
@RestController
@RequestMapping("/api/master-data/storage-blocks")
public class StorageBlockController {

    private final StorageBlockRepository blocks;

    public StorageBlockController(StorageBlockRepository blocks) {
        this.blocks = blocks;
    }

    @GetMapping
    public List<StorageBlock> list(@RequestParam UUID warehouseId,
                                   @RequestParam(required = false) String storageType) {
        return storageType != null
                ? blocks.findByWarehouseIdAndStorageType(warehouseId, storageType)
                : blocks.findByWarehouseId(warehouseId);
    }

    @PostMapping
    public ResponseEntity<StorageBlock> create(@RequestBody StorageBlock body) {
        body.setId(null);
        StorageBlock saved = blocks.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/storage-blocks/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public StorageBlock get(@PathVariable UUID id) {
        return blocks.findById(id).orElseThrow(() -> new NotFoundException("StorageBlock", id));
    }

    @PutMapping("/{id}")
    public StorageBlock update(@PathVariable UUID id, @RequestBody StorageBlock body) {
        StorageBlock existing = blocks.findById(id).orElseThrow(() -> new NotFoundException("StorageBlock", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return blocks.save(body);
    }
}
