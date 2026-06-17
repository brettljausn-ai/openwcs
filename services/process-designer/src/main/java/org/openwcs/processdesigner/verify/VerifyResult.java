package org.openwcs.processdesigner.verify;

import java.util.List;
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
 *   <li>{@code uomCode} — matchedBarcode.uomCode (barcode) or the base uom code (sku); null for location,
 *       and null for a skuScan that matched a SKU code with more than one UOM (operator must pick).</li>
 *   <li>{@code schemaCategory} — attributeSchema.category (barcode/sku); null otherwise.</li>
 *   <li>{@code matchedAs} — how the value resolved: {@code "barcode"} (a product barcode matched) or
 *       {@code "sku"} (a SKU code matched); null for location and not-found. For the combined
 *       {@code skuScan} kind this tells the runtime which path fired.</li>
 *   <li>{@code uoms} — the resolved SKU's full UOM list as {@code {code, baseUnit}} entries, for the
 *       runtime's UOM picker. Empty for location and not-found.</li>
 *   <li>{@code needsUomChoice} — true only for a {@code skuScan} that matched a SKU code with more than
 *       one UOM (the runtime must prompt the operator to pick a UOM). False otherwise: a barcode pins
 *       its UOM, a single-UOM SKU auto-picks, and the plain {@code sku} kind never prompts.</li>
 *   <li>{@code detail} — the full master-data graph for the kind (uoms/barcodes/attributeSchema/
 *       matchedBarcode for sku, the location object for location). Empty map when not found.</li>
 *   <li>{@code fields} — the authoritative per-kind resolvable-field VALUES, keyed by the field keys
 *       in {@code VerifyFields} for the matched kind (sku-like: id/code/name/uomCode/schemaCategory;
 *       location: id/code/purpose/locationType/status). This is the map the runtime reads to apply a
 *       screen's {@code verify.write}; the top-level id/code/name/uomCode/schemaCategory remain for
 *       backward compatibility. Empty map when not found.</li>
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
        String matchedAs,
        List<Map<String, Object>> uoms,
        boolean needsUomChoice,
        Map<String, Object> detail,
        Map<String, Object> fields) {
}
