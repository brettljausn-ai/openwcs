package org.openwcs.masterdata.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only "resolve a scanned code" results for handheld scan verification
 * (process-designer scan steps). Every resolve returns HTTP 200; a miss is signalled with
 * {@code found:false} and null payload (never 404) so a process flow can branch on it and
 * store the resolved ids.
 *
 * <p>Two shapes live here:
 * <ul>
 *   <li>{@link SkuResolveView} — a SKU resolved by a scanned barcode value or by SKU code, with
 *       its full linked graph: the matched barcode (barcode-by-value only), the SKU identity, ALL
 *       of the SKU's units of measure, ALL of its barcodes, and the warehouse-scoped attribute
 *       schema (via the {@code SkuProfile(skuId, warehouseId)} overlay).</li>
 *   <li>{@link LocationResolveView} — a location resolved by (warehouse, code).</li>
 * </ul>
 */
public final class ScanResolveView {

    private ScanResolveView() {
    }

    /**
     * SKU resolve result. For barcode-by-value resolves {@code matchedBarcode} carries the exact
     * scanned barcode and its packaging level; for SKU-code resolves it is null. {@code ambiguous}
     * is true only when a scanned barcode value matched more than one SKU (the first is returned).
     */
    public record SkuResolveView(
            boolean found,
            boolean ambiguous,
            MatchedBarcode matchedBarcode,
            SkuRef sku,
            List<UomRef> uoms,
            List<BarcodeRef> barcodes,
            AttributeSchemaRef attributeSchema) {

        /** The not-found result (HTTP 200, everything null/empty). */
        public static SkuResolveView notFound() {
            return new SkuResolveView(false, false, null, null, List.of(), List.of(), null);
        }
    }

    /** Location resolve result; {@code location} is null when no location matched (HTTP 200). */
    public record LocationResolveView(boolean found, LocationRef location) {

        public static LocationResolveView notFound() {
            return new LocationResolveView(false, null);
        }
    }

    /** The exact barcode that was scanned, resolved to its packaging level and symbology. */
    public record MatchedBarcode(String value, UUID uomId, String uomCode, String type) {
    }

    /** SKU identity. */
    public record SkuRef(UUID skuId, String code, String description, String status) {
    }

    /** One of the SKU's units of measure (full bundle row). */
    public record UomRef(UUID uomId, String code, boolean baseUnit, UUID parentUomId, BigDecimal qtyInParent) {
    }

    /** One of the SKU's barcodes, with its packaging level code and symbology. */
    public record BarcodeRef(String value, String uomCode, String type) {
    }

    /** The warehouse-scoped attribute schema linked through the SKU's profile overlay. */
    public record AttributeSchemaRef(UUID attributeSchemaId, String category, int version, Map<String, Object> jsonSchema) {
    }

    /** Location identity (resolved by warehouse + code). */
    public record LocationRef(UUID locationId, String code, String locationType, String purpose, String status) {
    }
}
