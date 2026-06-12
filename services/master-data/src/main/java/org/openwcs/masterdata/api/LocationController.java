package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.domain.Location;
import org.openwcs.masterdata.repo.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** Location / topology CRUD (build.md §6). */
@RestController
@RequestMapping("/api/master-data/locations")
public class LocationController {

    private static final Logger log = LoggerFactory.getLogger(LocationController.class);

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
    public ResponseEntity<Location> create(
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody Location body) {
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(null);
        Location saved = locations.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/locations/" + saved.getId())).body(saved);
    }

    /** Bulk-create locations (used by the guided storage-block builder to generate rack cells). */
    @PostMapping("/bulk")
    public ResponseEntity<List<Location>> createBulk(
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody List<Location> body) {
        for (Location l : body) {
            requireWarehouse(warehouses, l.getWarehouseId());
            l.setId(null);
        }
        List<Location> saved = locations.saveAll(body);
        if (!saved.isEmpty()) {
            Location first = saved.get(0);
            log.info("bulk location create: {} locations created in warehouse {} for block {}"
                            + " (codes {} .. {}) because the storage-block builder generated them",
                    saved.size(), first.getWarehouseId(), first.getBlockId(),
                    first.getCode(), saved.get(saved.size() - 1).getCode());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * The warehouse's operational location for a piece of equipment (conveyor, lift, ...), a
     * workplace, or the UNKNOWN catch-all, created lazily on first use. HUs are always booked to
     * a real location (product rule): each conveyor and workplace automatically has a location
     * carrying its name, and an HU whose position is not known books to UNKNOWN (the inventory
     * service bars that location's stock from allocation). Idempotent: concurrent first calls
     * resolve to the same row via the (warehouse_id, code) unique constraint.
     */
    @GetMapping("/operational")
    public Location operational(
            @RequestParam UUID warehouseId,
            @RequestParam String kind,
            @RequestParam(required = false) String name) {
        String code;
        String locationType;
        String purpose;
        switch (kind) {
            case "EQUIPMENT" -> {
                code = requireName(kind, name);
                locationType = "CONVEYOR_SEGMENT";
                purpose = "TRANSPORT";
            }
            case "WORKPLACE" -> {
                code = requireName(kind, name);
                locationType = "STATION";
                purpose = "STAGING";
            }
            case "UNKNOWN" -> {
                code = "UNKNOWN";
                locationType = "FREE_SPACE";
                purpose = "QUARANTINE";
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported operational-location kind '" + kind + "' (expected EQUIPMENT, WORKPLACE or UNKNOWN).");
        }
        return locations.findByWarehouseIdAndCode(warehouseId, code)
                .orElseGet(() -> createOperational(warehouseId, code, locationType, purpose, kind));
    }

    private Location createOperational(
            UUID warehouseId, String code, String locationType, String purpose, String kind) {
        Location location = new Location();
        location.setWarehouseId(warehouseId);
        location.setCode(code);
        location.setLocationType(locationType);
        location.setPurpose(purpose);
        location.setStatus("ACTIVE");
        try {
            Location saved = locations.save(location);
            log.info("operational location created: {} ({}) in warehouse {} as {}/{} because a {}"
                            + " booking referenced it for the first time",
                    code, saved.getId(), warehouseId, locationType, purpose, kind);
            return saved;
        } catch (DataIntegrityViolationException concurrentCreate) {
            // A concurrent first use won the (warehouse_id, code) unique constraint: retry-fetch
            // the winner's row so both callers resolve to the same location.
            log.info("operational location create lost a concurrent race: {} in warehouse {} already exists,"
                    + " returning the existing row", code, warehouseId);
            return locations.findByWarehouseIdAndCode(warehouseId, code).orElseThrow(() -> concurrentCreate);
        }
    }

    private static String requireName(String kind, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "An operational location of kind " + kind + " needs a name (it becomes the location code).");
        }
        return name;
    }

    @GetMapping("/{id}")
    public Location get(@PathVariable UUID id) {
        return locations.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
    }

    @PutMapping("/{id}")
    public Location update(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody Location body) {
        Location existing = locations.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(id);
        body.setVersion(existing.getVersion());
        return locations.save(body);
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        Location existing = locations.findById(id).orElseThrow(() -> new NotFoundException("Location", id));
        existing.setStatus("ARCHIVED");
        locations.save(existing);
        return ResponseEntity.noContent().build();
    }
}
