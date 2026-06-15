package org.openwcs.orders.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Service-level aggregates for shipped OUTBOUND orders: on-time-to-cutoff compliance and
 * order cycle time. Read-only, warehouse-scoped, best-effort: figures degrade to {@code null}
 * (today / per-day) when no outbound order shipped in the relevant period, so an empty
 * warehouse still renders.
 *
 * <p>Today is the current UTC day; the window spans the last {@code days} UTC days, oldest
 * first. The order lifecycle keeps no dispatch timestamp, so an order's ship time is taken
 * from {@code updated_at} of SHIPPED orders (shipping is their final transition), matching
 * {@link org.openwcs.orders.service.OrderFlowReportService}'s convention.
 *
 * <ul>
 *   <li><b>onTimeToCutoffPctToday</b>: of outbound orders shipped today that carry a cut-off
 *       ({@code dispatchBy}), the percent shipped at or before it. {@code null} when no such
 *       order shipped today.</li>
 *   <li><b>orderCycleTimeMedianMinToday</b>: median created -> shipped duration (minutes) over
 *       outbound orders shipped today. {@code null} when none shipped today.</li>
 *   <li><b>perDay</b>: one bucket per UTC day of the window, the same two figures per day
 *       ({@code null} on days with no shipments).</li>
 * </ul>
 */
public record SlaReport(
        Double onTimeToCutoffPctToday,
        Double orderCycleTimeMedianMinToday,
        List<DayBucket> perDay) {

    /** One UTC day's SLA figures; null fields on days with no outbound shipments. */
    public record DayBucket(LocalDate day, Double onTimePct, Double cycleMedianMin) {
    }
}
