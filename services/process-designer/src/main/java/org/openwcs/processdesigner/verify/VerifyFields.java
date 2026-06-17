package org.openwcs.processdesigner.verify;

import java.util.LinkedHashMap;
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
 */
public final class VerifyFields {

    /** One resolvable field: its stable key (used in write maps and {@code VerifyResult.fields}) and a short human label. */
    public record Field(String key, String label) {
    }

    /** SKU-like fields (barcode / sku / skuScan all resolve a SKU). */
    private static final List<Field> SKU_FIELDS = List.of(
            new Field("id", "SKU ID"),
            new Field("code", "SKU code"),
            new Field("name", "Description"),
            new Field("uomCode", "Unit of measure"),
            new Field("schemaCategory", "Attribute category"));

    /** Location fields. */
    private static final List<Field> LOCATION_FIELDS = List.of(
            new Field("id", "Location ID"),
            new Field("code", "Location code"),
            new Field("purpose", "Purpose"),
            new Field("locationType", "Location type"),
            new Field("status", "Status"));

    /** Per-kind catalog, keyed by verify kind. Insertion order matches {@link VerifyKinds#ALL}. */
    private static final Map<String, List<Field>> BY_KIND;

    static {
        Map<String, List<Field>> m = new LinkedHashMap<>();
        m.put(VerifyKinds.BARCODE, SKU_FIELDS);
        m.put(VerifyKinds.SKU, SKU_FIELDS);
        m.put(VerifyKinds.LOCATION, LOCATION_FIELDS);
        m.put(VerifyKinds.SKU_SCAN, SKU_FIELDS);
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

    /** The valid field keys for one kind (for publish validation of {@code verify.write}). */
    public static Set<String> keysForKind(String kind) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (Field f : forKind(kind)) {
            keys.add(f.key());
        }
        return keys;
    }
}
