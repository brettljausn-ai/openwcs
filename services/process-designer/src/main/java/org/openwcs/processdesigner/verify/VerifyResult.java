package org.openwcs.processdesigner.verify;

import java.util.Map;

/**
 * Normalised scan-verify resolution the handheld runtime branches on and stores from. The proxy
 * flattens the master-data resolve graph into a small, kind-agnostic shape: the resolved
 * {@code id}/{@code code}/{@code name} (plus {@code uomCode} + {@code schemaCategory} for SKUs) for
 * easy {@code write} mapping, and the full master-data {@code detail} graph for anything richer.
 *
 * <ul>
 *   <li>{@code found} — whether master-data matched the value (false is a clean passthrough, not an error).</li>
 *   <li>{@code ambiguous} — barcode matched more than one SKU (master-data flag; null/false otherwise).</li>
 *   <li>{@code id} — sku.skuId (barcode/sku) or location.locationId (location); null when not found.</li>
 *   <li>{@code code} — sku.code or location.code; null when not found.</li>
 *   <li>{@code name} — sku.description (barcode/sku); null for location.</li>
 *   <li>{@code uomCode} — matchedBarcode.uomCode (barcode) or the base uom code (sku); null for location.</li>
 *   <li>{@code schemaCategory} — attributeSchema.category (barcode/sku); null otherwise.</li>
 *   <li>{@code detail} — the full master-data graph for the kind (uoms/barcodes/attributeSchema/
 *       matchedBarcode for sku, the location object for location). Empty map when not found.</li>
 * </ul>
 */
public record VerifyResult(
        boolean found,
        Boolean ambiguous,
        String id,
        String code,
        String name,
        String uomCode,
        String schemaCategory,
        Map<String, Object> detail) {
}
