package org.openwcs.orders.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Order-flow report for the Reporting screen, one per direction (INBOUND / OUTBOUND).
 *
 * <p>Status mapping (documented because the lifecycle has no explicit start/complete columns):
 * <ul>
 *   <li><b>expected</b>: INBOUND = received orders with no stock posted yet (not cancelled,
 *       zero line transactions); OUTBOUND = received but not yet released (status CREATED).</li>
 *   <li><b>active</b>: INBOUND = orders still awaiting stock (not cancelled, some line not
 *       fully received) including the expected ones; OUTBOUND = released and in progress
 *       (RELEASED / PARTIALLY_ALLOCATED / ALLOCATED / NOT_FULFILLABLE / CUBING_FAILED).</li>
 *   <li><b>started</b>: physical work has begun, i.e. the active subset with at least one
 *       posted line transaction (receipt / pick).</li>
 * </ul>
 *
 * <p>perDay buckets received (created), started (first posted transaction) and completed
 * orders per UTC day over the window; hourOfDay buckets all orders received in the window
 * by the hour of their created timestamp (shows intake peaks).
 */
public record OrderFlowReport(
        long expected,
        long active,
        long started,
        List<DayBucket> perDay,
        List<HourBucket> hourOfDay) {

    /** One UTC day of the window: orders received / started / completed that day. */
    public record DayBucket(LocalDate day, long received, long started, long completed) {
    }

    /** Orders received in the window, bucketed by hour-of-day (0..23) of creation. */
    public record HourBucket(int hour, long count) {
    }
}
