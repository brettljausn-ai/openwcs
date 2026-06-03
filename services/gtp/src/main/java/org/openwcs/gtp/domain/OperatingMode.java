package org.openwcs.gtp.domain;

/**
 * What the operator does at a GTP station when an HU is presented — orthogonal to the station's
 * destination topology ({@link GtpStation#getMode()} = ORDER_LOCATION | PUT_WALL). A station
 * supports a set of these; each {@link WorkCycle} runs exactly one.
 *
 * <ul>
 *   <li>{@link #PICKING} — the existing present-stock → put-to-light flow (unchanged).</li>
 *   <li>{@link #DECANTING} — move stock from a source HU into a target HU's compartments; the
 *       filled target is then ready for slotting put-away (a documented seam).</li>
 *   <li>{@link #STOCK_COUNT} — cycle counting: record counted qty per SKU, compute variance vs
 *       expected; emit a stock-count/adjustment intent (a seam to inventory).</li>
 *   <li>{@link #QC} — record an inspection verdict per HU/SKU: PASS | FAIL | HOLD.</li>
 *   <li>{@link #MAINTENANCE} — request HUs/empty carriers for mechanical checks; record a
 *       condition outcome: OK | DEFECTIVE | REPAIR.</li>
 * </ul>
 */
public enum OperatingMode {
    PICKING,
    DECANTING,
    STOCK_COUNT,
    QC,
    MAINTENANCE;

    /** Parse a mode name, throwing {@link IllegalArgumentException} with a helpful message. */
    public static OperatingMode parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("operating mode must not be null");
        }
        try {
            return OperatingMode.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "unknown operating mode: " + value + " (expected one of " + java.util.Arrays.toString(values()) + ")");
        }
    }
}
