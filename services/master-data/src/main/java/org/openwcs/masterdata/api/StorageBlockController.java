package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.domain.StorageBlock;
import org.openwcs.masterdata.repo.StorageBlockRepository;
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
    public ResponseEntity<StorageBlock> create(
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody StorageBlock body) {
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(null);
        StorageBlock saved = blocks.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/storage-blocks/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public StorageBlock get(@PathVariable UUID id) {
        return blocks.findById(id).orElseThrow(() -> new NotFoundException("StorageBlock", id));
    }

    @PutMapping("/{id}")
    public StorageBlock update(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody StorageBlock body) {
        StorageBlock existing = blocks.findById(id).orElseThrow(() -> new NotFoundException("StorageBlock", id));
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(id);
        body.setVersion(existing.getVersion());
        return blocks.save(body);
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }
}
