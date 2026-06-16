package org.openwcs.masterdata.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.openwcs.masterdata.domain.AttributeSchema;
import org.openwcs.masterdata.domain.Barcode;
import org.openwcs.masterdata.domain.Location;
import org.openwcs.masterdata.domain.Sku;
import org.openwcs.masterdata.domain.SkuProfile;
import org.openwcs.masterdata.domain.UnitOfMeasure;
import org.openwcs.masterdata.repo.AttributeSchemaRepository;
import org.openwcs.masterdata.repo.BarcodeRepository;
import org.openwcs.masterdata.repo.BarcodeTypeRepository;
import org.openwcs.masterdata.repo.LocationRepository;
import org.openwcs.masterdata.repo.SkuProfileRepository;
import org.openwcs.masterdata.repo.SkuRepository;
import org.openwcs.masterdata.repo.UnitOfMeasureRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only "resolve a scanned code" endpoints for handheld scan verification (the
 * process-designer scan steps). A handheld scans a barcode / SKU code / location code; these
 * resolve it to confirm it EXISTS and return the linked graph so a process can branch on
 * {@code found} and store the resolved ids.
 *
 * <p>By design these are best-effort and never 404: a miss returns HTTP 200 with
 * {@code found:false} and a null payload, so the flow can branch instead of erroring. They reuse
 * the same repositories the SKU/location card reads use, and inherit the open catalog-read RBAC
 * ({@code MASTER_DATA_VIEW}) that {@link RbacFilter} applies to every GET under
 * {@code /api/master-data}.
 */
@RestController
@RequestMapping("/api/master-data/resolve")
public class ScanResolveController {

    private final SkuRepository skus;
    private final SkuProfileRepository profiles;
    private final UnitOfMeasureRepository uoms;
    private final BarcodeRepository barcodes;
    private final BarcodeTypeRepository barcodeTypes;
    private final AttributeSchemaRepository attributeSchemas;
    private final LocationRepository locations;

    public ScanResolveController(SkuRepository skus, SkuProfileRepository profiles,
                                 UnitOfMeasureRepository uoms, BarcodeRepository barcodes,
                                 BarcodeTypeRepository barcodeTypes, AttributeSchemaRepository attributeSchemas,
                                 LocationRepository locations) {
        this.skus = skus;
        this.profiles = profiles;
        this.uoms = uoms;
        this.barcodes = barcodes;
        this.barcodeTypes = barcodeTypes;
        this.attributeSchemas = attributeSchemas;
        this.locations = locations;
    }

    /**
     * Resolve a scanned barcode value to its SKU and the full linked graph. If more than one SKU
     * carries the value, the first is returned with {@code ambiguous:true}. No match → found:false.
     */
    @GetMapping("/sku-by-barcode")
    public ScanResolveView.SkuResolveView skuByBarcode(@RequestParam UUID warehouseId, @RequestParam String code) {
        List<Barcode> matches = barcodes.findByValue(code);
        if (matches.isEmpty()) {
            return ScanResolveView.SkuResolveView.notFound();
        }
        // Prefer an exact single match; if a value spans multiple SKUs, take the first and flag it.
        boolean ambiguous = matches.stream().map(Barcode::getSkuId).distinct().count() > 1;
        Barcode matched = matches.get(0);
        Sku sku = skus.findById(matched.getSkuId()).orElse(null);
        if (sku == null) {
            return ScanResolveView.SkuResolveView.notFound();
        }
        List<UnitOfMeasure> skuUoms = uoms.findBySkuId(sku.getId());
        Map<UUID, String> uomCodeById = skuUoms.stream()
                .collect(Collectors.toMap(UnitOfMeasure::getId, UnitOfMeasure::getCode, (a, b) -> a));
        ScanResolveView.MatchedBarcode matchedBarcode = new ScanResolveView.MatchedBarcode(
                matched.getValue(),
                matched.getUomId(),
                matched.getUomId() == null ? null : uomCodeById.get(matched.getUomId()),
                barcodeTypeName(matched.getBarcodeTypeId()));
        return buildSkuGraph(sku, warehouseId, ambiguous, matchedBarcode, skuUoms, uomCodeById);
    }

    /** Resolve by SKU {@code code}. Same body shape as sku-by-barcode but {@code matchedBarcode} is null. */
    @GetMapping("/sku")
    public ScanResolveView.SkuResolveView skuByCode(@RequestParam UUID warehouseId, @RequestParam String code) {
        Sku sku = skus.findByCode(code).orElse(null);
        if (sku == null) {
            return ScanResolveView.SkuResolveView.notFound();
        }
        List<UnitOfMeasure> skuUoms = uoms.findBySkuId(sku.getId());
        Map<UUID, String> uomCodeById = skuUoms.stream()
                .collect(Collectors.toMap(UnitOfMeasure::getId, UnitOfMeasure::getCode, (a, b) -> a));
        return buildSkuGraph(sku, warehouseId, false, null, skuUoms, uomCodeById);
    }

    /** Resolve a scanned location code within a warehouse. No match → found:false. */
    @GetMapping("/location")
    public ScanResolveView.LocationResolveView location(@RequestParam UUID warehouseId, @RequestParam String code) {
        return locations.findByWarehouseIdAndCode(warehouseId, code)
                .map(loc -> new ScanResolveView.LocationResolveView(true, toLocationRef(loc)))
                .orElseGet(ScanResolveView.LocationResolveView::notFound);
    }

    // ------------------------------------------------------------------ helpers

    private ScanResolveView.SkuResolveView buildSkuGraph(Sku sku, UUID warehouseId, boolean ambiguous,
                                                         ScanResolveView.MatchedBarcode matchedBarcode,
                                                         List<UnitOfMeasure> skuUoms,
                                                         Map<UUID, String> uomCodeById) {
        List<ScanResolveView.UomRef> uomRefs = skuUoms.stream()
                .map(u -> new ScanResolveView.UomRef(
                        u.getId(), u.getCode(), u.isBaseUnit(), u.getParentUomId(), u.getQtyInParent()))
                .toList();
        List<ScanResolveView.BarcodeRef> barcodeRefs = barcodes.findBySkuId(sku.getId()).stream()
                .map(b -> new ScanResolveView.BarcodeRef(
                        b.getValue(),
                        b.getUomId() == null ? null : uomCodeById.get(b.getUomId()),
                        barcodeTypeName(b.getBarcodeTypeId())))
                .toList();
        ScanResolveView.AttributeSchemaRef schemaRef = resolveSchema(sku.getId(), warehouseId);
        ScanResolveView.SkuRef skuRef =
                new ScanResolveView.SkuRef(sku.getId(), sku.getCode(), sku.getDescription(), sku.getStatus());
        return new ScanResolveView.SkuResolveView(true, ambiguous, matchedBarcode, skuRef, uomRefs, barcodeRefs, schemaRef);
    }

    /** The warehouse-scoped attribute schema via SkuProfile(skuId, warehouseId).attributeSchemaId. */
    private ScanResolveView.AttributeSchemaRef resolveSchema(UUID skuId, UUID warehouseId) {
        if (warehouseId == null) {
            return null;
        }
        UUID schemaId = profiles.findBySkuIdAndWarehouseId(skuId, warehouseId)
                .map(SkuProfile::getAttributeSchemaId)
                .orElse(null);
        if (schemaId == null) {
            return null;
        }
        return attributeSchemas.findById(schemaId)
                .map(s -> new ScanResolveView.AttributeSchemaRef(
                        s.getId(), s.getCategory(), s.getSchemaVersion(), s.getJsonSchema()))
                .orElse(null);
    }

    private String barcodeTypeName(UUID barcodeTypeId) {
        if (barcodeTypeId == null) {
            return null;
        }
        return barcodeTypes.findById(barcodeTypeId).map(t -> t.getName()).orElse(null);
    }

    private ScanResolveView.LocationRef toLocationRef(Location loc) {
        return new ScanResolveView.LocationRef(
                loc.getId(), loc.getCode(), loc.getLocationType(), loc.getPurpose(), loc.getStatus());
    }
}
