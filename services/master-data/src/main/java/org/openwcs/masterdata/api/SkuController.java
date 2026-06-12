package org.openwcs.masterdata.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.masterdata.domain.Barcode;
import org.openwcs.masterdata.domain.DangerousGoods;
import org.openwcs.masterdata.domain.Sku;
import org.openwcs.masterdata.domain.SkuProfile;
import org.openwcs.masterdata.domain.UnitOfMeasure;
import org.openwcs.masterdata.repo.BarcodeRepository;
import org.openwcs.masterdata.repo.DangerousGoodsRepository;
import org.openwcs.masterdata.repo.SkuProfileRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.UnitOfMeasureRepository;
import org.openwcs.masterdata.service.SkuSyncService;
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

/**
 * SKU catalog + per-SKU sub-resources (build.md §4.1, §6).
 *
 * <p>SKU core, units-of-measure and barcodes are <em>host-owned</em> master data: the WCS is a
 * slave to them (build.md §6, §16). They are readable/searchable here, but interactive create/edit/
 * delete is rejected via {@link HostManagedGuard}; only the host-sync ingestion path (a direct
 * internal call with no gateway identity header) may upsert them. SKU profiles and dangerous-goods
 * are WCS-owned overlays and stay editable.
 */
@RestController
@RequestMapping("/api/master-data/skus")
public class SkuController {

    private final SkuRepository skus;
    private final SkuProfileRepository profiles;
    private final UnitOfMeasureRepository uoms;
    private final BarcodeRepository barcodes;
    private final DangerousGoodsRepository dangerousGoods;
    private final SkuSyncService skuSync;

    public SkuController(SkuRepository skus, SkuProfileRepository profiles, UnitOfMeasureRepository uoms,
                         BarcodeRepository barcodes, DangerousGoodsRepository dangerousGoods,
                         SkuSyncService skuSync) {
        this.skus = skus;
        this.profiles = profiles;
        this.uoms = uoms;
        this.barcodes = barcodes;
        this.dangerousGoods = dangerousGoods;
        this.skuSync = skuSync;
    }

    // ---------------------------------------------------------------- SKU core
    @GetMapping
    public PageResponse<Sku> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String ownerClient) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("code"));
        if (code != null) {
            List<Sku> match = skus.findByCode(code).map(List::of).orElseGet(List::of);
            return new PageResponse<>(match, 0, size, match.size(), match.isEmpty() ? 0 : 1);
        }
        Page<Sku> result;
        if (q != null) {
            result = skus.search(q, pageable);
        } else if (ownerClient != null) {
            result = skus.findByOwnerClientIgnoreCase(ownerClient, pageable);
        } else {
            result = skus.findAll(pageable);
        }
        return PageResponse.of(result);
    }

    @PostMapping
    public ResponseEntity<Sku> create(@RequestBody Sku body, HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "SKU");
        body.setId(null);
        Sku saved = skus.save(body);
        return ResponseEntity.created(URI.create("/api/master-data/skus/" + saved.getId())).body(saved);
    }

    @GetMapping("/{skuId}")
    public Sku get(@PathVariable UUID skuId) {
        return requireSku(skuId);
    }

    @PutMapping("/{skuId}")
    public Sku update(@PathVariable UUID skuId, @RequestBody Sku body, HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "SKU");
        Sku existing = requireSku(skuId);
        body.setId(skuId);
        body.setVersion(existing.getVersion());
        return skus.save(body);
    }

    @DeleteMapping("/{skuId}")
    public ResponseEntity<Void> archive(@PathVariable UUID skuId, HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "SKU");
        Sku existing = requireSku(skuId);
        existing.setStatus("ARCHIVED");
        skus.save(existing);
        return ResponseEntity.noContent().build();
    }

    // --------------------------------------------------------------- Bulk load
    @PostMapping("/import")
    public BulkImportReport importSkus(@RequestBody List<Sku> incoming, HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "SKU");
        int created = 0;
        int updated = 0;
        List<BulkImportReport.ImportError> errors = new ArrayList<>();
        for (int row = 0; row < incoming.size(); row++) {
            Sku sku = incoming.get(row);
            try {
                Optional<Sku> existing = sku.getCode() == null ? Optional.empty() : skus.findByCode(sku.getCode());
                if (existing.isPresent()) {
                    sku.setId(existing.get().getId());
                    sku.setVersion(existing.get().getVersion());
                    skus.save(sku);
                    updated++;
                } else {
                    sku.setId(null);
                    skus.save(sku);
                    created++;
                }
            } catch (RuntimeException e) {
                errors.add(new BulkImportReport.ImportError(row, sku.getCode(), e.getMessage()));
            }
        }
        return new BulkImportReport(incoming.size(), created, updated, errors.size(), errors);
    }

    /**
     * Host-driven sync of a list of SKUs, each carrying its unit-of-measure hierarchy and barcodes
     * inline. The host is authoritative: a SKU's stored UoMs/barcodes absent from the push are
     * removed, the rest upserted (see {@link SkuSyncService}). Host-owned, so interactive callers
     * are rejected; only the header-less host-sync path may write.
     */
    @PostMapping("/sync")
    public SkuSyncService.SyncReport sync(@RequestBody List<@Valid SkuSyncRequest> incoming,
                                          HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "SKU");
        return skuSync.sync(incoming);
    }

    /**
     * One-call product card for operator screens (GTP tote panel): SKU identity + base-UoM
     * dimensions + the warehouse profile's metadata. Read-only convenience over existing data;
     * same open catalog-read RBAC as the other GETs here (MASTER_DATA_VIEW via {@link RbacFilter}).
     */
    @GetMapping("/{skuId}/card")
    public SkuCardView card(@PathVariable UUID skuId, @RequestParam(required = false) UUID warehouseId) {
        Sku sku = requireSku(skuId);
        SkuCardView.BaseUom baseUom = uoms.findBySkuIdAndBaseUnitTrue(skuId)
                .map(u -> new SkuCardView.BaseUom(
                        u.getCode(), u.getLengthMm(), u.getWidthMm(), u.getHeightMm(), u.getWeightG()))
                .orElse(null);
        Map<String, Object> metadata = warehouseId == null
                ? Map.of()
                : profiles.findBySkuIdAndWarehouseId(skuId, warehouseId)
                        .map(SkuProfile::getMetadata)
                        .orElseGet(Map::of);
        return new SkuCardView(sku.getId(), sku.getCode(), sku.getDescription(), sku.getImageUrl(),
                baseUom, metadata);
    }

    // --------------------------------------------------------- SkuProfile (per warehouse)
    @GetMapping("/{skuId}/profiles")
    public List<SkuProfile> listProfiles(@PathVariable UUID skuId) {
        requireSku(skuId);
        return profiles.findBySkuId(skuId);
    }

    @PutMapping("/{skuId}/profiles")
    public SkuProfile upsertProfile(@PathVariable UUID skuId, @RequestBody SkuProfile body) {
        requireSku(skuId);
        Optional<SkuProfile> existing = profiles.findBySkuIdAndWarehouseId(skuId, body.getWarehouseId());
        body.setSkuId(skuId);
        if (existing.isPresent()) {
            body.setId(existing.get().getId());
            body.setVersion(existing.get().getVersion());
        } else {
            body.setId(null);
        }
        return profiles.save(body);
    }

    // ------------------------------------------------------------- UnitsOfMeasure
    @GetMapping("/{skuId}/uoms")
    public List<UnitOfMeasure> listUoms(@PathVariable UUID skuId) {
        requireSku(skuId);
        return uoms.findBySkuId(skuId);
    }

    @PostMapping("/{skuId}/uoms")
    public ResponseEntity<UnitOfMeasure> createUom(@PathVariable UUID skuId, @RequestBody UnitOfMeasure body,
                                                   HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "Unit of measure");
        requireSku(skuId);
        body.setId(null);
        body.setSkuId(skuId);
        return ResponseEntity.status(201).body(uoms.save(body));
    }

    // -------------------------------------------------------------------- Barcodes
    @GetMapping("/{skuId}/barcodes")
    public List<Barcode> listBarcodes(@PathVariable UUID skuId) {
        requireSku(skuId);
        return barcodes.findBySkuId(skuId);
    }

    @PostMapping("/{skuId}/barcodes")
    public ResponseEntity<Barcode> createBarcode(@PathVariable UUID skuId, @RequestBody Barcode body,
                                                 HttpServletRequest request) {
        HostManagedGuard.rejectInteractiveWrite(request, "Barcode");
        requireSku(skuId);
        body.setId(null);
        body.setSkuId(skuId);
        return ResponseEntity.status(201).body(barcodes.save(body));
    }

    // -------------------------------------------------------------- DangerousGoods
    @GetMapping("/{skuId}/dangerous-goods")
    public DangerousGoods getDangerousGoods(@PathVariable UUID skuId) {
        requireSku(skuId);
        return dangerousGoods.findBySkuId(skuId)
                .orElseThrow(() -> new NotFoundException("DangerousGoods for SKU", skuId));
    }

    @PutMapping("/{skuId}/dangerous-goods")
    public DangerousGoods putDangerousGoods(@PathVariable UUID skuId, @RequestBody DangerousGoods body) {
        requireSku(skuId);
        Optional<DangerousGoods> existing = dangerousGoods.findBySkuId(skuId);
        body.setSkuId(skuId);
        if (existing.isPresent()) {
            body.setId(existing.get().getId());
            body.setVersion(existing.get().getVersion());
        } else {
            body.setId(null);
        }
        return dangerousGoods.save(body);
    }

    @DeleteMapping("/{skuId}/dangerous-goods")
    public ResponseEntity<Void> deleteDangerousGoods(@PathVariable UUID skuId) {
        DangerousGoods existing = dangerousGoods.findBySkuId(skuId)
                .orElseThrow(() -> new NotFoundException("DangerousGoods for SKU", skuId));
        dangerousGoods.delete(existing);
        return ResponseEntity.noContent().build();
    }

    private Sku requireSku(UUID skuId) {
        return skus.findById(skuId).orElseThrow(() -> new NotFoundException("SKU", skuId));
    }
}
