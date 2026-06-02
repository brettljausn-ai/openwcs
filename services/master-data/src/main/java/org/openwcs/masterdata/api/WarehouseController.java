package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.UUID;
import org.openwcs.masterdata.domain.Warehouse;
import org.openwcs.masterdata.repo.WarehouseRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

/** Warehouse CRUD (build.md §6). */
@RestController
@RequestMapping("/api/master-data/warehouses")
public class WarehouseController {

    private final WarehouseRepository warehouses;

    public WarehouseController(WarehouseRepository warehouses) {
        this.warehouses = warehouses;
    }

    @GetMapping
    public PageResponse<Warehouse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("code"));
        Page<Warehouse> result = status != null
                ? warehouses.findByStatus(status, pageable)
                : warehouses.findAll(pageable);
        return PageResponse.of(result);
    }

    @PostMapping
    public ResponseEntity<Warehouse> create(@RequestBody Warehouse body) {
        body.setId(null);
        Warehouse saved = warehouses.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/warehouses/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public Warehouse get(@PathVariable UUID id) {
        return warehouses.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
    }

    @PutMapping("/{id}")
    public Warehouse update(@PathVariable UUID id, @RequestBody Warehouse body) {
        Warehouse existing = warehouses.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return warehouses.save(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        Warehouse existing = warehouses.findById(id).orElseThrow(() -> new NotFoundException("Warehouse", id));
        existing.setStatus("ARCHIVED");
        warehouses.save(existing);
        return ResponseEntity.noContent().build();
    }
}
