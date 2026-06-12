package org.openwcs.flow.service;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.openwcs.flow.api.ReportingDtos.DecisionLatencyStats;
import org.springframework.stereotype.Component;

/**
 * In-process latency tracker for the per-scan routing decision ({@code RoutingService.decide}).
 * Keeps the last {@link #CAPACITY} end-to-end decision durations in a fixed-size ring buffer and
 * serves percentile snapshots for {@code GET /api/flow/reports/decision-latency}.
 *
 * <p>Deliberately per-instance and in-memory: the point is "is THIS replica answering scans within
 * the hard-real-time budget right now", not durable history (the daily reporting counters cover
 * history). Recording is two writes on the scan hot path (an atomic increment + an array store),
 * so the metric itself costs nanoseconds. Stats reads copy the buffer and sort; they run on the
 * report endpoint, never on the scan path.
 *
 * <p>Thread-safety: slots are claimed by an atomic counter, so concurrent decisions never write
 * the same slot. A stats read may observe a slot mid-overwrite (a torn window, not torn longs:
 * long stores are atomic on 64-bit JVMs) — fine for an observability percentile.
 */
@Component
public class DecisionLatencyTracker {

    /** Ring capacity; a power of two so the slot index is a cheap mask. */
    static final int CAPACITY = 4096;

    private final long[] samplesNanos = new long[CAPACITY];
    private final AtomicLong recorded = new AtomicLong();

    /** Record one decision's end-to-end duration. Called once per scan, hot path. */
    public void record(long nanos) {
        long slot = recorded.getAndIncrement();
        samplesNanos[(int) (slot & (CAPACITY - 1))] = nanos;
    }

    /** Percentiles over the ring's current window (the last up-to-{@value #CAPACITY} decisions). */
    public DecisionLatencyStats stats() {
        long total = recorded.get();
        int n = (int) Math.min(total, CAPACITY);
        if (n == 0) {
            return new DecisionLatencyStats(0, 0, 0, 0, 0);
        }
        long[] window = Arrays.copyOf(samplesNanos, n);
        Arrays.sort(window);
        return new DecisionLatencyStats(n,
                ms(percentile(window, 50)),
                ms(percentile(window, 95)),
                ms(percentile(window, 99)),
                ms(window[n - 1]));
    }

    /** Nearest-rank percentile over an ascending-sorted, non-empty window. */
    private static long percentile(long[] sorted, int pct) {
        int rank = (int) Math.ceil(pct / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    /** Nanos to milliseconds with microsecond resolution (sub-ms latencies stay visible). */
    private static double ms(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;
    }
}
