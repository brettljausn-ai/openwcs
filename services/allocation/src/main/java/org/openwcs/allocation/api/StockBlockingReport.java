package org.openwcs.allocation.api;

/**
 * Stock-blocking KPIs for one warehouse (read-only, best-effort). A "blocked" line is an
 * outbound order line that cannot currently be fulfilled from pickable stock — an allocation
 * line in {@code SHORT} status on an order that is not cancelled.
 *
 * @param blockedLines outbound lines currently unfulfillable from pickable stock
 * @param distinctSkusShort distinct SKUs across those blocked lines
 * @param recoverableLines blocked lines whose SKU still has stock somewhere (reserve / ASRS)
 * @param zeroStockLines blocked lines whose SKU has no stock anywhere
 * @param openOutboundLines all non-cancelled outbound order lines (denominator for a % alert)
 */
public record StockBlockingReport(
        long blockedLines,
        long distinctSkusShort,
        long recoverableLines,
        long zeroStockLines,
        long openOutboundLines) {
}
