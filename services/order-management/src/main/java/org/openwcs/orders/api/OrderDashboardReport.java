package org.openwcs.orders.api;

import java.util.List;

/**
 * Live dashboard aggregate for the operations overview screen: headline open/today figures
 * for inbound and outbound plus a per-hour intake/throughput split for today. Read-only,
 * warehouse-scoped, best-effort: every figure degrades to 0 / {@code null} rather than
 * failing the whole response, so a partial database read still renders the dashboard.
 *
 * <p>Today is the current UTC day, matching how the order timestamps are written.
 *
 * <ul>
 *   <li><b>open</b>: orders of the direction not yet finished (INBOUND: not cancelled and
 *       some line not fully received; OUTBOUND: not SHIPPED and not CANCELLED).</li>
 *   <li><b>expectedToday</b> (inbound): orders created today (intake expected to be worked).</li>
 *   <li><b>releasedToday</b> (outbound): orders that moved past CREATED with an update today.</li>
 *   <li><b>linesPickedToday</b>: distinct outbound lines with a PICK transaction posted today.</li>
 *   <li><b>shortsToday</b>: outbound lines in SHORT status updated today.</li>
 *   <li><b>projectedFinishIso</b>: ISO-8601 instant when the current open backlog is projected
 *       to finish at today's completion rate; {@code null} if no backlog or no rate yet.</li>
 *   <li><b>perHour</b>: today's UTC hours (0..23), inbound = receipts posted, outbound = picks
 *       posted, zero-filled.</li>
 * </ul>
 */
public record OrderDashboardReport(Inbound inbound, Outbound outbound, List<HourSplit> perHour) {

    /** Inbound headline figures. */
    public record Inbound(long open, long expectedToday, String projectedFinishIso) {
    }

    /** Outbound headline figures. */
    public record Outbound(
            long open,
            long releasedToday,
            long linesPickedToday,
            long shortsToday,
            String projectedFinishIso) {
    }

    /** One UTC hour of today: posted receipts (inbound) vs posted picks (outbound). */
    public record HourSplit(int hour, long inbound, long outbound) {
    }
}
