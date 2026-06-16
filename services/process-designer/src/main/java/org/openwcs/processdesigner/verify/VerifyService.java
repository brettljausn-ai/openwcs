package org.openwcs.processdesigner.verify;

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

    public VerifyService(RestClient.Builder builder,
                         @Value("${openwcs.process-designer.master-data-base-url:http://localhost:8081}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public VerifyResult verify(VerifyRequest request) {
        String kind = request.kind();
        if (!VerifyKinds.isValid(kind)) {
            throw new IllegalArgumentException(
                    "Unknown verify kind '" + kind + "' (expected one of " + VerifyKinds.ALL + ").");
        }
        String warehouseId = request.warehouseId().toString();
        String code = request.code();

        Map<String, Object> body = switch (kind) {
            case VerifyKinds.BARCODE -> get("/api/master-data/resolve/sku-by-barcode", warehouseId, code);
            case VerifyKinds.SKU -> get("/api/master-data/resolve/sku", warehouseId, code);
            case VerifyKinds.LOCATION -> get("/api/master-data/resolve/location", warehouseId, code);
            default -> throw new IllegalStateException("unreachable");
        };

        boolean found = asBool(body.get("found"));
        if (!found) {
            return new VerifyResult(false, asBoolOrNull(body.get("ambiguous")),
                    null, null, null, null, null, Map.of());
        }
        return VerifyKinds.LOCATION.equals(kind) ? normaliseLocation(body) : normaliseSku(kind, body);
    }

    private VerifyResult normaliseSku(String kind, Map<String, Object> body) {
        Map<String, Object> sku = asMap(body.get("sku"));
        Map<String, Object> matchedBarcode = asMap(body.get("matchedBarcode"));
        Map<String, Object> attributeSchema = asMap(body.get("attributeSchema"));
        List<Object> uoms = asList(body.get("uoms"));

        String id = asString(sku.get("skuId"));
        String code = asString(sku.get("code"));
        String name = asString(sku.get("description"));

        // uomCode: the scanned barcode's uom for a barcode scan; the SKU's base uom otherwise.
        String uomCode = VerifyKinds.BARCODE.equals(kind) ? asString(matchedBarcode.get("uomCode")) : null;
        if (uomCode == null) {
            uomCode = baseUomCode(uoms);
        }
        String schemaCategory = asString(attributeSchema.get("category"));

        Map<String, Object> detail = new HashMap<>();
        detail.put("uoms", uoms);
        detail.put("barcodes", asList(body.get("barcodes")));
        detail.put("attributeSchema", body.get("attributeSchema"));
        detail.put("matchedBarcode", body.get("matchedBarcode"));

        return new VerifyResult(true, asBoolOrNull(body.get("ambiguous")),
                id, code, name, uomCode, schemaCategory, detail);
    }

    private VerifyResult normaliseLocation(Map<String, Object> body) {
        Map<String, Object> location = asMap(body.get("location"));
        String id = asString(location.get("locationId"));
        String code = asString(location.get("code"));
        Map<String, Object> detail = new HashMap<>(location);
        return new VerifyResult(true, null, id, code, null, null, null, detail);
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
