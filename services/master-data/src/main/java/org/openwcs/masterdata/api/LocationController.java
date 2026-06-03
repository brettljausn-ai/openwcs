package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.UUID;
import org.openwcs.masterdata.domain.Location;
import org.openwcs.masterdata.repo.LocationRepository;
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

/** Location / topology CRUD (build.md §6). */
@RestController
@RequestMapping("/api/master-data/locations")
public class LocationController {

    private final LocationRepository locations;

    public LocationController(LocationRepository locations) {
        this.locations = locations;
    }

    @GetMapping
    public PageResponse<Location> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam UUID warehouseId,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String locationType,
            @RequestParam(required = false) UUID parentId,
            @RequestParam(required = false) UUID blockId) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("code"));
        return PageResponse.of(locations.search(warehouseId, purpose, locationType, parentId, blockId, pageable));
    }

    @PostMapping
    public ResponseEntity<Location> create(@RequestBody Location body) {
        body.setId(null);
        Location saved = locations.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/locations/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public Location get(@PathVariable UUID id) {
        return locations.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
    }

    @PutMapping("/{id}")
    public Location update(@PathVariable UUID id, @RequestBody Location body) {
        Location existing = locations.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
        body.setId(id);
        body.setVersion(existing.getVersion());
        return locations.save(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        Location existing = locations.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
        existing.setStatus("ARCHIVED");
        locations.save(existing);
        return ResponseEntity.noContent().build();
    }
}
