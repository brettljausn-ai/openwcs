package org.openwcs.allocation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openwcs.allocation.client.MasterDataClient;

/**
 * Splits a base-unit pick quantity into a pick-type UoM breakdown (ADR 0002), gated by
 * the warehouse's allowed pick types. Largest-first: full cases (if CASE is allowed and
 * a case pack size is known), then the remainder as EACH (or SPLIT_CASE).
 *
 * <p>Convention: a SKU's case pack size is the {@code qtyInParent} of its UoM coded
 * "CASE" (interpreted as base units per case). If absent or CASE is not allowed, the
 * whole quantity is picked as eaches.
 */
public final class PickBreakdown {

    private PickBreakdown() {
    }

    public static Map<String, Integer> split(long qty, List<String> allowedPickTypes, int caseSize) {
        Map<String, Integer> result = new LinkedHashMap<>();
        long remaining = qty;

        if (allowedPickTypes.contains("CASE") && caseSize > 1) {
            long cases = remaining / caseSize;
            if (cases > 0) {
                result.put("CASE", (int) cases);
                remaining -= cases * caseSize;
            }
        }
        if (remaining > 0) {
            String eachType = allowedPickTypes.contains("EACH") ? "EACH"
                    : allowedPickTypes.contains("SPLIT_CASE") ? "SPLIT_CASE"
                    : "EACH"; // fallback when only CASE is allowed but a sub-case remainder exists
            result.merge(eachType, (int) remaining, Integer::sum);
        }
        return result;
    }

    /** Base units per case for a SKU, from its UoM coded "CASE"; 1 if none / not applicable. */
    public static int caseSize(List<MasterDataClient.UomDef> uoms) {
        if (uoms == null) {
            return 1;
        }
        return uoms.stream()
                .filter(u -> u.code() != null && u.code().equalsIgnoreCase("CASE") && u.qtyInParent() != null)
                .map(u -> u.qtyInParent().intValue())
                .filter(v -> v > 1)
                .findFirst()
                .orElse(1);
    }
}
