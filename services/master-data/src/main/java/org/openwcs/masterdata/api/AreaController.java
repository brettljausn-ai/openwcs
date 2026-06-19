package org.openwcs.masterdata.api;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.openwcs.common.security.AccessControl;
import org.openwcs.masterdata.domain.Area;
import org.openwcs.masterdata.repo.AreaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Area registry CRUD (Master Data Areas epic): hierarchical groupings of locations within a
 * warehouse. Reads are open to any catalog viewer (the {@link RbacFilter} gates GETs with
 * MASTER_DATA_VIEW); creates / updates / deletes are admin-gated, mirroring the storage-block
 * registry. Two cross-cutting guards apply on write: the parent area must live in the same
 * warehouse, and an area may never become its own ancestor (cycle guard).
 */
@RestController
@RequestMapping("/api/master-data/areas")
public class AreaController {

    private static final Logger log = LoggerFactory.getLogger(AreaController.class);

    private final AreaRepository areas;

    public AreaController(AreaRepository areas) {
        this.areas = areas;
    }

    @GetMapping
    public List<Area> list(
            @RequestParam UUID warehouseId,
            @RequestParam(required = false) UUID parentAreaId) {
        return parentAreaId != null
                ? areas.findByWarehouseIdAndParentAreaId(warehouseId, parentAreaId)
                : areas.findByWarehouseId(warehouseId);
    }

    @PostMapping
    public ResponseEntity<Area> create(
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody Area body) {
        requireAdmin(roles);
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(null);
        validateParent(body, null);
        Area saved = areas.save(body);
        log.info("area created: {} ({}) in warehouse {} under parent {} because an admin created it",
                saved.getCode(), saved.getId(), saved.getWarehouseId(), saved.getParentAreaId());
        return ResponseEntity.created(URI.create("/api/master-data/areas/" + saved.getId())).body(saved);
    }

    @GetMapping("/{id}")
    public Area get(@PathVariable UUID id) {
        return areas.findById(id).orElseThrow(() -> new NotFoundException("Area", id));
    }

    @PutMapping("/{id}")
    public Area update(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles,
            @RequestHeader(name = "X-Auth-Warehouses", required = false) String warehouses,
            @RequestBody Area body) {
        requireAdmin(roles);
        Area existing = areas.findById(id).orElseThrow(() -> new NotFoundException("Area", id));
        requireWarehouse(warehouses, body.getWarehouseId());
        body.setId(id);
        body.setVersion(existing.getVersion());
        validateParent(body, id);
        return areas.save(body);
    }

    /** Archive an area (admin-gated). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(
            @PathVariable UUID id,
            @RequestHeader(name = "X-Auth-Roles", required = false) String roles) {
        requireAdmin(roles);
        Area existing = areas.findById(id).orElseThrow(() -> new NotFoundException("Area", id));
        existing.setStatus("ARCHIVED");
        areas.save(existing);
        log.info("area archived: {} ({}) in warehouse {} because an admin requested it",
                existing.getCode(), id, existing.getWarehouseId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The location ids covered by an area. With {@code recursive=true} (default) it expands the
     * area's subtree (the area plus all descendant areas) via a recursive CTE; with
     * {@code recursive=false} it returns only locations assigned directly to this area.
     */
    @GetMapping("/{id}/location-ids")
    public List<UUID> locationIds(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "true") boolean recursive) {
        areas.findById(id).orElseThrow(() -> new NotFoundException("Area", id));
        return recursive ? areas.findLocationIdsInSubtree(id) : areas.findLocationIdsDirect(id);
    }

    /**
     * Resolve an area reference (its code, case-insensitive) within a warehouse. Best-effort: a miss
     * returns {@code found:false} with a null area (still HTTP 200), so callers can branch on it.
     */
    @GetMapping("/resolve")
    public AreaResolveView resolve(@RequestParam UUID warehouseId, @RequestParam String ref) {
        return areas.findByWarehouseIdAndCodeIgnoreCase(warehouseId, ref)
                .map(a -> new AreaResolveView(true, new AreaRef(a.getId(), a.getCode(), a.getName())))
                .orElseGet(() -> new AreaResolveView(false, null));
    }

    // ------------------------------------------------------------------ guards

    /**
     * Reject a parent that lives in a different warehouse, or that would make the area its own
     * ancestor (walk the parent chain up from the proposed parent; if we reach the area being saved,
     * it is a cycle). {@code selfId} is null on create (a new area has no id yet, so no cycle).
     */
    private void validateParent(Area body, UUID selfId) {
        UUID parentId = body.getParentAreaId();
        if (parentId == null) {
            return;
        }
        if (selfId != null && parentId.equals(selfId)) {
            throw new IllegalArgumentException("An area cannot be its own parent.");
        }
        Area parent = areas.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent area " + parentId + " does not exist."));
        if (!parent.getWarehouseId().equals(body.getWarehouseId())) {
            throw new IllegalArgumentException("The parent area must be in the same warehouse.");
        }
        if (selfId != null) {
            Set<UUID> seen = new HashSet<>();
            UUID cursor = parentId;
            while (cursor != null) {
                if (cursor.equals(selfId)) {
                    throw new IllegalArgumentException(
                            "The parent area is a descendant of this area, which would create a cycle.");
                }
                if (!seen.add(cursor)) {
                    break; // defensive: stop on any pre-existing loop in the stored data
                }
                cursor = areas.findById(cursor).map(Area::getParentAreaId).orElse(null);
            }
        }
    }

    private static void requireAdmin(String roles) {
        if (!AccessControl.parseRoles(roles).contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Managing areas is admin-only.");
        }
    }

    /** 403 if the caller is warehouse-scoped and the body targets a warehouse outside their set. */
    private static void requireWarehouse(String warehouses, UUID warehouseId) {
        if (!AccessControl.warehouseAllowed(warehouses, warehouseId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not permitted for this warehouse.");
        }
    }

    /** Result of resolving an area reference. {@code area} is null on a miss. */
    public record AreaResolveView(boolean found, AreaRef area) {
    }

    /** Area identity returned by a resolve. */
    public record AreaRef(UUID id, String code, String name) {
    }
}
