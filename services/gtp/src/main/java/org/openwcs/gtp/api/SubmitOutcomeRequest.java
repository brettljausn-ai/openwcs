package org.openwcs.gtp.api;

import java.math.BigDecimal;

/**
 * Submit the per-line outcome of a non-PICKING task line:
 *
 * <ul>
 *   <li>DECANT_MOVE — {@code actualQty} = the qty actually moved into the target compartment.</li>
 *   <li>COUNT_ENTRY — {@code actualQty} = the counted qty (variance is computed vs the expected).</li>
 *   <li>QC_VERDICT — {@code verdict} ∈ {PASS, FAIL, HOLD}.</li>
 *   <li>MAINTENANCE_CHECK — {@code verdict} ∈ {OK, DEFECTIVE, REPAIR}.</li>
 * </ul>
 */
public record SubmitOutcomeRequest(
        BigDecimal actualQty,
        String verdict) {
}
