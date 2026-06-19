package org.openwcs.processdesigner.verify;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-kind catalog of resolvable scan-verify fields: which {@code {key, label}} pairs a given
 * verify kind can produce, and therefore which keys a screen's {@code verify.write} may map and the
 * keys that appear in {@link VerifyResult#fields()}. This is the single source of truth shared by the
 * designer ({@code GET /capabilities} -> {@code verifyFields}), the runtime ({@link VerifyResult}), and
 * publish validation ({@code DefinitionValidator}), so all three agree on what a location vs a SKU can
 * resolve. A location has different attributes than a SKU, so the field sets differ per kind.
 *
 * <p>A field is either a SCALAR (its value in {@link VerifyResult#fields()} is a plain string) or an
 * OBJECT ({@code object=true}): an object field's value is a nested {@code {subKey -> value}} map and
 * it carries a {@code sub} list of the drill-down sub-fields. A screen's {@code verify.write} key may
 * therefore be a scalar key ({@code uomCode}), an object field key for the whole object ({@code uom},
 * stores the nested map), or a dotted object sub-field ({@code uom.factor}, stores one property). The
 * object mechanism is fully generic: any {@link Field} may be an object with {@code sub} fields;
 * nothing is hardcoded beyond the catalog entries below.
 */
public final class VerifyFields {

    /**
     * One resolvable field. A scalar field has {@code object=false} and {@code sub=List.of()} (its
     * value is a string). An object field has {@code object=true} and a non-empty {@code sub} list (its
     * value is a nested {@code {subKey -> value}} map; each sub is itself a {@link Field}, currently a
     * scalar). {@code object}/{@code sub} are omitted from JSON when empty so scalar fields keep the
     * original {@code {key, label}} shape.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public record Field(String key, String label, boolean object,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Field> sub) {

        /** A scalar field: a stable key and a short human label. */
        public static Field scalar(String key, String label) {
            return new Field(key, label, false, List.of());
        }

        /** An object field: a key, a label, and its drill-down sub-fields (each a scalar). */
        public static Field object(String key, String label, List<Field> sub) {
            return new Field(key, label, true, List.copyOf(sub));
        }

        /** The valid sub-field keys for this object field (empty for a scalar field). */
        public Set<String> subKeys() {
            Set<String> keys = new LinkedHashSet<>();
            for (Field f : sub) {
                keys.add(f.key());
            }
            return keys;
        }
    }

    /** SKU-like fields (barcode / sku / skuScan all resolve a SKU). */
    private static final List<Field> SKU_FIELDS = List.of(
            Field.scalar("id", "SKU ID"),
            Field.scalar("code", "SKU code"),
            Field.scalar("name", "Description"),
            Field.scalar("uomCode", "Unit of measure"),
            Field.scalar("schemaCategory", "Attribute category"),
            // The resolved SKU as a whole object (store it whole, or drill into one property).
            Field.object("sku", "SKU (object)", List.of(
                    Field.scalar("skuId", "SKU ID"),
                    Field.scalar("code", "Code"),
                    Field.scalar("description", "Description"),
                    Field.scalar("status", "Status"))),
            // The MATCHED unit of measure (the scanned barcode's uom; chosen/base uom for sku/skuScan).
            Field.object("uom", "Unit of measure (object)", List.of(
                    Field.scalar("uomId", "UOM ID"),
                    Field.scalar("code", "Code"),
                    Field.scalar("factor", "Factor (qty in parent)"),
                    Field.scalar("baseUnit", "Is base unit"))));

    /** Location fields. */
    private static final List<Field> LOCATION_FIELDS = List.of(
            Field.scalar("id", "Location ID"),
            Field.scalar("code", "Location code"),
            Field.scalar("purpose", "Purpose"),
            Field.scalar("locationType", "Location type"),
            Field.scalar("status", "Status"),
            // The resolved location as a whole object (store it whole, or drill into one property).
            Field.object("location", "Location (object)", List.of(
                    Field.scalar("locationId", "Location ID"),
                    Field.scalar("code", "Code"),
                    Field.scalar("locationType", "Type"),
                    Field.scalar("purpose", "Purpose"),
                    Field.scalar("status", "Status"))));

    /** Order/picksheet fields (kind order resolves an order header via order-management). */
    private static final List<Field> ORDER_FIELDS = List.of(
            Field.scalar("id", "Order ID"),
            Field.scalar("code", "Order reference"),
            Field.scalar("status", "Status"),
            Field.scalar("orderType", "Order type"),
            Field.scalar("customerRef", "Customer reference"),
            Field.scalar("lineCount", "Line count"),
            // The resolved order as a whole object (store it whole, or drill into one property).
            Field.object("order", "Order (object)", List.of(
                    Field.scalar("orderId", "Order ID"),
                    Field.scalar("orderRef", "Order reference"),
                    Field.scalar("orderType", "Order type"),
                    Field.scalar("status", "Status"),
                    Field.scalar("customerRef", "Customer reference"),
                    Field.scalar("lineCount", "Line count"))));

    /** ASN fields (kind asn resolves an inbound order/ASN header; same shape, inbound-phrased labels). */
    private static final List<Field> ASN_FIELDS = List.of(
            Field.scalar("id", "Order ID"),
            Field.scalar("code", "ASN reference"),
            Field.scalar("status", "Status"),
            Field.scalar("orderType", "Order type"),
            Field.scalar("customerRef", "Customer reference"),
            Field.scalar("lineCount", "Line count"),
            // The resolved ASN/order as a whole object (store it whole, or drill into one property).
            Field.object("asn", "ASN (object)", List.of(
                    Field.scalar("orderId", "Order ID"),
                    Field.scalar("orderRef", "ASN reference"),
                    Field.scalar("orderType", "Order type"),
                    Field.scalar("status", "Status"),
                    Field.scalar("customerRef", "Customer reference"),
                    Field.scalar("lineCount", "Line count"))));

    /** Area fields (kind area resolves a warehouse area header via master-data). */
    private static final List<Field> AREA_FIELDS = List.of(
            Field.scalar("areaId", "Area ID"),
            Field.scalar("areaCode", "Area code"),
            Field.scalar("name", "Area name"),
            // The resolved area as a whole object (store it whole, or drill into one property).
            Field.object("area", "Area (object)", List.of(
                    Field.scalar("id", "Area ID"),
                    Field.scalar("code", "Code"),
                    Field.scalar("name", "Name"))));

    /** Per-kind catalog, keyed by verify kind. Insertion order matches {@link VerifyKinds#ALL}. */
    private static final Map<String, List<Field>> BY_KIND;

    static {
        Map<String, List<Field>> m = new LinkedHashMap<>();
        m.put(VerifyKinds.BARCODE, SKU_FIELDS);
        m.put(VerifyKinds.SKU, SKU_FIELDS);
        m.put(VerifyKinds.LOCATION, LOCATION_FIELDS);
        m.put(VerifyKinds.SKU_SCAN, SKU_FIELDS);
        m.put(VerifyKinds.ORDER, ORDER_FIELDS);
        m.put(VerifyKinds.ASN, ASN_FIELDS);
        m.put(VerifyKinds.AREA, AREA_FIELDS);
        BY_KIND = Map.copyOf(m);
    }

    private VerifyFields() {
    }

    /** The full per-kind catalog for {@code GET /capabilities}. Keyed by verify kind; ordered field lists. */
    public static Map<String, List<Field>> catalog() {
        // Rebuild in VerifyKinds.ALL order so the capabilities payload is deterministic.
        Map<String, List<Field>> out = new LinkedHashMap<>();
        for (String kind : VerifyKinds.ALL) {
            out.put(kind, BY_KIND.get(kind));
        }
        return out;
    }

    /** The resolvable fields for one kind (empty list for an unknown kind). */
    public static List<Field> forKind(String kind) {
        return BY_KIND.getOrDefault(kind, List.of());
    }

    /** The valid root field keys for one kind (for publish validation of {@code verify.write}). */
    public static Set<String> keysForKind(String kind) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Field f : forKind(kind)) {
            keys.add(f.key());
        }
        return keys;
    }

    /** The field with the given root key for a kind, or null if the kind has no such field. */
    public static Field field(String kind, String key) {
        for (Field f : forKind(kind)) {
            if (f.key().equals(key)) {
                return f;
            }
        }
        return null;
    }
}
