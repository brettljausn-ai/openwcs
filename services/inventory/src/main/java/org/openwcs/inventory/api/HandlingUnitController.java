package org.openwcs.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.openwcs.inventory.client.MasterDataClient;
import org.openwcs.inventory.domain.HandlingUnit;
import org.openwcs.inventory.domain.Stock;
import org.openwcs.inventory.repo.HandlingUnitRepository;
import org.openwcs.inventory.repo.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.openwcs.inventory.service.HandlingUnitNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Handling-unit registry: the physical units (barcode + type + location) that hold stock. */
@RestController
@RequestMapping("/api/inventory/handling-units")
public class HandlingUnitController {

    private static final Logger log = LoggerFactory.getLogger(HandlingUnitController.class);

    private final HandlingUnitRepository handlingUnits;
    private final StockRepository stock;
    private final MasterDataClient masterData;

    public HandlingUnitController(
            HandlingUnitRepository handlingUnits, StockRepository stock, MasterDataClient masterData) {
        this.handlingUnits = handlingUnits;
        this.stock = stock;
        this.masterData = masterData;
    }

    /** All handling units registered in a warehouse. */
    @GetMapping
    public List<HandlingUnit> list(@RequestParam UUID warehouseId) {
        return handlingUnits.findByWarehouseId(warehouseId);
    }

    /** Count of ACTIVE handling units of a given type (across warehouses) — guards HU-type archiving. */
    @GetMapping("/active-count")
    public long activeCount(@RequestParam UUID huTypeId) {
        return handlingUnits.countByHuTypeIdAndStatus(huTypeId, "ACTIVE");
    }

    @GetMapping("/{id}")
    public HandlingUnit get(@PathVariable UUID id) {
        return handlingUnits.findById(id)
                .orElseThrow(() -> new HandlingUnitNotFoundException(id));
    }

    @PostMapping
    public ResponseEntity<HandlingUnit> create(@RequestBody HandlingUnit handlingUnit) {
        handlingUnit.setHuId(null);
        HandlingUnit saved = handlingUnits.save(handlingUnit);
        return ResponseEntity
                .created(URI.create("/api/inventory/handling-units/" + saved.getHuId()))
                .body(saved);
    }

    @PutMapping("/{id}")
    public HandlingUnit update(@PathVariable UUID id, @RequestBody HandlingUnit handlingUnit) {
        HandlingUnit existing = handlingUnits.findById(id)
                .orElseThrow(() -> new HandlingUnitNotFoundException(id));
        handlingUnit.setHuId(id);
        // The HU's type and current location are NOT editable here — they change only through a
        // controlled process (maintenance / QA). Preserve the existing values regardless of the body.
        handlingUnit.setHuTypeId(existing.getHuTypeId());
        handlingUnit.setLocationId(existing.getLocationId());
        return handlingUnits.save(handlingUnit);
    }

    /**
     * Book the HU's current location through the transport lifecycle (the controlled process the
     * full PUT above defers to). HUs are ALWAYS booked to a real location (product rule): a tote on
     * a conveyor books to the conveyor's own location, a tote at a workplace to the workplace's.
     * When the caller does not know the position ({@code locationId} = null) the HU books to the
     * warehouse's UNKNOWN location instead, where its stock stays visible but is barred from
     * allocation. If master-data cannot resolve UNKNOWN, the booking is rejected with 503 (callers
     * treat the booking as best-effort).
     */
    @PutMapping("/{id}/location")
    @Transactional
    public HandlingUnit updateLocation(@PathVariable UUID id, @RequestBody LocationUpdateRequest request) {
        HandlingUnit existing = handlingUnits.findById(id)
                .orElseThrow(() -> new HandlingUnitNotFoundException(id));
        UUID fromLocationId = existing.getLocationId();
        UUID targetLocationId = request.locationId();
        boolean unknownPosition = targetLocationId == null;
        if (unknownPosition) {
            // No nulls reach the stock rows (stock.location_id is NOT NULL): a position-less
            // booking goes to the warehouse's UNKNOWN location. Throws 503 when master-data is down.
            targetLocationId = masterData.unknownLocationId(existing.getWarehouseId());
        }
        existing.setLocationId(targetLocationId);
        // The stock RIDES IN the tote: its rows' location must follow the HU, or the stock overview
        // keeps pointing at the slot the tote left (observed live after retrieves and dig-out moves).
        //
        // Rows that would COLLIDE at the target merge first: the bucket key is (warehouse, sku,
        // batch, location, hu, status), and after the move every row shares one location — so two
        // rows differing only by location (e.g. a counting adjustment that landed on a stale
        // location) must become ONE row, or this whole booking dies on stock_bucket_uniq and rolls
        // back (observed live: DEMO-HU-004 stayed booked at its station after storing back).
        int followed = 0;
        int merged = 0;
        java.util.Map<String, Stock> survivors = new java.util.LinkedHashMap<>();
        for (Stock s : stock.findByHuId(id)) {
            String bucket = s.getSkuId() + "|" + s.getBatchId() + "|" + s.getStatus();
            Stock survivor = survivors.get(bucket);
            if (survivor == null) {
                survivors.put(bucket, s);
                continue;
            }
            if (s.getUomCode() != null && !s.getUomCode().equals(survivor.getUomCode())) {
                log.warn("merging stock buckets with DIFFERENT UoMs on hu {} ({}): sku {} has {} {} and"
                                + " {} {} — quantities summed, check the upstream booking",
                        existing.getCode(), id, s.getSkuId(), survivor.getQty(), survivor.getUomCode(),
                        s.getQty(), s.getUomCode());
            }
            survivor.setQty(survivor.getQty().add(s.getQty()));
            stock.delete(s);
            merged++;
            log.info("stock buckets merged on hu {} ({}): sku {} status {} now one row of qty {} —"
                            + " the rows differed only by location and would collide when the stock"
                            + " follows the hu to {}",
                    existing.getCode(), id, s.getSkuId(), s.getStatus(), survivor.getQty(),
                    targetLocationId);
        }
        if (merged > 0) {
            // The duplicates' DELETEs must hit the database before the survivors' location UPDATEs,
            // or the flush order (updates before deletes) re-creates the very collision we removed.
            stock.flush();
        }
        for (Stock s : survivors.values()) {
            s.setLocationId(targetLocationId);
            stock.save(s);
            followed++;
        }
        HandlingUnit saved = handlingUnits.save(existing);
        log.info("hu location booked: hu {} ({}) moved from location {} to {}{} because of a transport"
                        + " lifecycle update; {} stock row(s) followed the hu",
                saved.getCode(), id, fromLocationId, targetLocationId,
                unknownPosition ? " (UNKNOWN: the caller did not know the position)" : "", followed);
        return saved;
    }
}
