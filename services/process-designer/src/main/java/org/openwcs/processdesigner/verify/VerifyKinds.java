package org.openwcs.processdesigner.verify;

import java.util.List;
import java.util.Set;

/**
 * The fixed set of scan-verify kinds the screen "Verify" step supports. Served (as a list) by
 * {@code GET /capabilities} so the designer's Verify picker is server-driven, and used by both the
 * verify proxy and publish validation so the three definitions stay in one place.
 */
public final class VerifyKinds {

    public static final String BARCODE = "barcode";
    public static final String SKU = "sku";
    public static final String LOCATION = "location";
    /**
     * Combined SKU scan: the scanned value can be either a product barcode (UOM pinned by the barcode)
     * or a SKU code (UOM auto-picked when the SKU has a single UOM, otherwise the runtime prompts).
     */
    public static final String SKU_SCAN = "skuScan";

    /** Ordered for the designer picker. */
    public static final List<String> ALL = List.of(BARCODE, SKU, LOCATION, SKU_SCAN);

    private static final Set<String> SET = Set.copyOf(ALL);

    private VerifyKinds() {
    }

    public static boolean isValid(String kind) {
        return kind != null && SET.contains(kind);
    }
}
