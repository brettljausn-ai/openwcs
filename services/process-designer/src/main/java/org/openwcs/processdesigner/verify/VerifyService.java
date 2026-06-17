package org.openwcs.processdesigner.verify;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Scan-verify proxy: resolves a scanned/typed value against master-data and normalises the result so
 * the handheld runtime can branch on / store from it. Read-only and stateless (no instance/ledger
 * persistence). It calls the matching master-data resolve endpoint via a {@link RestClient} bound to
 * {@code master-data-base-url}; the identity-forwarding customizer rides the operator's
 * {@code X-Auth-*} headers along, so master-data RBAC + warehouse scope see the original operator and
 * the handheld never hits master-data directly.
 *
 * <p>The master-data resolve endpoints return 200 with {@code found:false} on a miss (never 404), so
 * a not-found is a clean passthrough. A transport/4xx/5xx failure becomes a {@link VerifyException}
 * (mapped to 502).
 */
@Service
public class VerifyService {

    private final RestClient http;
    private final RestClient orders;

    public VerifyService(RestClient.Builder builder,
                         @Value("${openwcs.process-designer.master-data-base-url:http://localhost:8081}") String baseUrl,
                         @Value("${openwcs.process-designer.orders-base-url:http://localhost:8084}") String ordersBaseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
        // A second client bound to order-management for the order/asn kinds. The identity-forwarding
        // customizer rides the operator's X-Auth-* along, same as the master-data client.
        this.orders = builder.clone().baseUrl(ordersBaseUrl).build();
    }

    public VerifyResult verify(VerifyRequest request) {
        String kind = request.kind();
        if (!VerifyKinds.isValid(kind)) {
            throw new IllegalArgumentException(
                    "Unknown verify kind '" + kind + "' (expected one of " + VerifyKinds.ALL + ").");
        }
        String warehouseId = request.warehouseId().toString();
        String code = request.code();

        if (VerifyKinds.SKU_SCAN.equals(kind)) {
            return verifySkuScan(warehouseId, code);
        }

        if (VerifyKinds.ORDER.equals(kind) || VerifyKinds.ASN.equals(kind)) {
            return verifyOrder(kind, warehouseId, code);
        }

        Map<String, Object> body = switch (kind) {
            case VerifyKinds.BARCODE -> get("/api/master-data/resolve/sku-by-barcode", warehouseId, code);
            case VerifyKinds.SKU -> get("/api/master-data/resolve/sku", warehouseId, code);
            case VerifyKinds.LOCATION -> get("/api/master-data/resolve/location", warehouseId, code);
            default -> throw new IllegalStateException("unreachable");
        };

        boolean found = asBool(body.get("found"));
        if (!found) {
            return notFound(body);
        }
        return VerifyKinds.LOCATION.equals(kind) ? normaliseLocation(body) : normaliseSku(kind, body);
    }

    /**
     * Combined SKU scan. First try the value as a product barcode (the barcode pins the UOM); if no
     * barcode matches, try it as a SKU code (a single-UOM SKU auto-picks, a multi-UOM SKU asks the
     * runtime to prompt).
     */
    private VerifyResult verifySkuScan(String warehouseId, String code) {
        Map<String, Object> byBarcode = get("/api/master-data/resolve/sku-by-barcode", warehouseId, code);
        if (asBool(byBarcode.get("found"))) {
            // A barcode matched: matchedAs=barcode, UOM pinned by the barcode, no prompt.
            return normaliseSku(VerifyKinds.BARCODE, byBarcode);
        }

        Map<String, Object> bySku = get("/api/master-data/resolve/sku", warehouseId, code);
        if (asBool(bySku.get("found"))) {
            // A SKU code matched: matchedAs=sku. One UOM auto-picks; more than one prompts.
            return normaliseSku(VerifyKinds.SKU, bySku);
        }

        // Neither matched. Carry the SKU-resolve ambiguous flag if present.
        return notFound(bySku);
    }

    /**
     * Order/ASN resolve. Calls order-management {@code GET /api/orders/resolve?warehouseId=&ref=<code>}
     * and normalises {@code {found, order}} into the verify shape. The same call backs both kinds;
     * {@code asn} is intended for inbound but is not hard-rejected on an orderType mismatch (a flow may
     * branch on the resolved {@code orderType}). The object field key is {@code order} for kind
     * {@code order} and {@code asn} for kind {@code asn}; both carry the same sub-fields.
     */
    private VerifyResult verifyOrder(String kind, String warehouseId, String code) {
        java.net.URI uri = UriComponentsBuilder.fromPath("/api/orders/resolve")
                .queryParam("warehouseId", warehouseId)
                .queryParam("ref", code)
                .build()
                .encode()
                .toUri();
        Map<String, Object> body;
        try {
            Map<String, Object> response = orders.get()
                    .uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            body = response == null ? Map.of() : response;
        } catch (RestClientException e) {
            throw new VerifyException("Verify call to order-management failed: " + e.getMessage(), e);
        }

        if (!asBool(body.get("found"))) {
            return notFound(body);
        }
        Map<String, Object> order = asMap(body.get("order"));
        String orderId = asString(order.get("orderId"));
        String orderRef = asString(order.get("orderRef"));
        String orderType = asString(order.get("orderType"));
        String status = asString(order.get("status"));
        String customerRef = asString(order.get("customerRef"));
        Object lineCount = order.get("lineCount");

        Map<String, Object> detail = new HashMap<>(order);

        // Scalars (keys must match the VerifyFields catalog for the kind).
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", orderId);
        fields.put("code", orderRef);
        fields.put("status", status);
        fields.put("orderType", orderType);
        fields.put("customerRef", customerRef);
        fields.put("lineCount", lineCount);
        // Object field: "order" for kind order, "asn" for kind asn; same sub-fields either way.
        Map<String, Object> orderObject = new HashMap<>();
        orderObject.put("orderId", orderId);
        orderObject.put("orderRef", orderRef);
        orderObject.put("orderType", orderType);
        orderObject.put("status", status);
        orderObject.put("customerRef", customerRef);
        orderObject.put("lineCount", lineCount);
        fields.put(kind, orderObject);

        // id/code/name surface the order ref + best-effort customer name. matchedAs stays null (its
        // enum is barcode|sku); a flow branches on the resolved orderType via the fields/object instead.
        return new VerifyResult(true, null, orderId, orderRef, customerRef,
                null, null, null, List.of(), false, detail, fields);
    }

    private VerifyResult normaliseSku(String kind, Map<String, Object> body) {
        Map<String, Object> sku = asMap(body.get("sku"));
        Map<String, Object> matchedBarcode = asMap(body.get("matchedBarcode"));
        Map<String, Object> attributeSchema = asMap(body.get("attributeSchema"));
        List<Object> rawUoms = asList(body.get("uoms"));
        List<Map<String, Object>> uoms = uomList(rawUoms);

        String id = asString(sku.get("skuId"));
        String code = asString(sku.get("code"));
        String name = asString(sku.get("description"));

        boolean barcodeMatch = VerifyKinds.BARCODE.equals(kind);
        String matchedAs = barcodeMatch ? "barcode" : "sku";

        // uomCode: the scanned barcode's uom for a barcode match; otherwise the SKU's UOM, but only
        // when it is unambiguous (single UOM -> auto-pick, preferring the base unit).
        String uomCode;
        boolean needsUomChoice;
        if (barcodeMatch) {
            // The barcode pins the UOM (fall back to the SKU's base uom if the barcode omitted it).
            uomCode = asString(matchedBarcode.get("uomCode"));
            if (uomCode == null) {
                uomCode = baseUomCode(rawUoms);
            }
            needsUomChoice = false;
        } else if (rawUoms.size() > 1) {
            // A SKU code with more than one UOM: the runtime must prompt the operator.
            uomCode = null;
            needsUomChoice = true;
        } else {
            // A SKU code with a single (or zero) UOM: auto-pick it (prefer the base unit).
            uomCode = baseUomCode(rawUoms);
            if (uomCode == null && !rawUoms.isEmpty() && rawUoms.get(0) instanceof Map<?, ?> only) {
                uomCode = asString(only.get("code"));
            }
            needsUomChoice = false;
        }
        String schemaCategory = asString(attributeSchema.get("category"));

        Map<String, Object> detail = new HashMap<>();
        detail.put("uoms", rawUoms);
        detail.put("barcodes", asList(body.get("barcodes")));
        detail.put("attributeSchema", body.get("attributeSchema"));
        detail.put("matchedBarcode", body.get("matchedBarcode"));

        // The MATCHED unit of measure: a barcode pins its uom by id; for sku/skuScan use the chosen
        // uomCode, else the base uom. Resolved against the uoms list; qtyInParent maps to factor.
        Map<String, Object> matchedUom = matchedUom(rawUoms, matchedBarcode, uomCode);

        // Authoritative per-kind field VALUES (keys must match the VerifyFields catalog for the kind).
        // Scalars stay strings; object fields carry a nested {subKey -> value} map.
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", id);
        fields.put("code", code);
        fields.put("name", name);
        fields.put("uomCode", uomCode);
        fields.put("schemaCategory", schemaCategory);
        // Object field "sku": the resolved SKU as a whole object.
        Map<String, Object> skuObject = new HashMap<>();
        skuObject.put("skuId", id);
        skuObject.put("code", code);
        skuObject.put("description", name);
        skuObject.put("status", asString(sku.get("status")));
        fields.put("sku", skuObject);
        // Object field "uom": the matched/chosen UOM.
        fields.put("uom", matchedUom);

        return new VerifyResult(true, asBoolOrNull(body.get("ambiguous")),
                id, code, name, uomCode, schemaCategory, matchedAs, uoms, needsUomChoice, detail, fields);
    }

    /**
     * The matched UOM as the {@code uom} object field value ({@code uomId, code, factor, baseUnit}).
     * A barcode match resolves the uom by {@code matchedBarcode.uomId} against the uoms list; for
     * sku/skuScan the chosen {@code uomCode} (else the base uom) is resolved by code. {@code factor}
     * comes from the uom's {@code qtyInParent}. Missing keys are null/false (an unresolved uom yields
     * an all-null/false object).
     */
    private static Map<String, Object> matchedUom(List<Object> rawUoms, Map<String, Object> matchedBarcode,
                                                  String uomCode) {
        String matchedUomId = asString(matchedBarcode.get("uomId"));
        Map<String, Object> picked = null;
        for (Object u : rawUoms) {
            if (!(u instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> uom = (Map<String, Object>) u;
            if (matchedUomId != null && matchedUomId.equals(asString(uom.get("uomId")))) {
                picked = uom;
                break;
            }
            if (matchedUomId == null && uomCode != null && uomCode.equals(asString(uom.get("code")))) {
                picked = uom;
                break;
            }
        }
        if (picked == null) {
            // Fall back to the base uom (no parent) so the object reflects something usable.
            for (Object u : rawUoms) {
                if (u instanceof Map<?, ?> uom && uom.get("parentUomId") == null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> base = (Map<String, Object>) uom;
                    picked = base;
                    break;
                }
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("uomId", picked == null ? null : asString(picked.get("uomId")));
        out.put("code", picked == null ? null : asString(picked.get("code")));
        out.put("factor", picked == null ? null : picked.get("qtyInParent"));
        out.put("baseUnit", picked != null && asBool(picked.get("baseUnit")));
        return out;
    }

    private VerifyResult normaliseLocation(Map<String, Object> body) {
        Map<String, Object> location = asMap(body.get("location"));
        String id = asString(location.get("locationId"));
        String code = asString(location.get("code"));
        String purpose = asString(location.get("purpose"));
        String locationType = asString(location.get("locationType"));
        String status = asString(location.get("status"));
        Map<String, Object> detail = new HashMap<>(location);

        // Location resolvable fields (keys must match the VerifyFields catalog for kind=location).
        Map<String, Object> fields = new HashMap<>();
        fields.put("id", id);
        fields.put("code", code);
        fields.put("purpose", purpose);
        fields.put("locationType", locationType);
        fields.put("status", status);
        // Object field "location": the resolved location as a whole object.
        Map<String, Object> locationObject = new HashMap<>();
        locationObject.put("locationId", id);
        locationObject.put("code", code);
        locationObject.put("locationType", locationType);
        locationObject.put("purpose", purpose);
        locationObject.put("status", status);
        fields.put("location", locationObject);

        return new VerifyResult(true, null, id, code, null, null, null, null, List.of(), false, detail, fields);
    }

    private static VerifyResult notFound(Map<String, Object> body) {
        return new VerifyResult(false, asBoolOrNull(body.get("ambiguous")),
                null, null, null, null, null, null, List.of(), false, Map.of(), Map.of());
    }

    /** Project the master-data UOM graph down to the {@code {code, baseUnit}} entries the picker needs. */
    private static List<Map<String, Object>> uomList(List<Object> rawUoms) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object u : rawUoms) {
            if (u instanceof Map<?, ?> uom) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("code", asString(uom.get("code")));
                entry.put("baseUnit", asBool(uom.get("baseUnit")));
                out.add(entry);
            }
        }
        return out;
    }

    /** The base unit's code, i.e. the uom with no parent (qtyInParent/parentUomId absent). */
    private static String baseUomCode(List<Object> uoms) {
        for (Object u : uoms) {
            if (u instanceof Map<?, ?> uom && uom.get("parentUomId") == null) {
                return asString(uom.get("code"));
            }
        }
        return null;
    }

    private Map<String, Object> get(String path, String warehouseId, String code) {
        java.net.URI uri = UriComponentsBuilder.fromPath(path)
                .queryParam("warehouseId", warehouseId)
                .queryParam("code", code)
                .build()
                .encode()
                .toUri();
        try {
            Map<String, Object> response = http.get()
                    .uri(uri)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return response == null ? Map.of() : response;
        } catch (RestClientException e) {
            throw new VerifyException("Verify call to master-data failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean asBool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static Boolean asBoolOrNull(Object value) {
        return value instanceof Boolean b ? b : null;
    }
}
