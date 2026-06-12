package org.openwcs.flow.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Response rows for the Reporting endpoints ({@code /api/flow/reports/*}). All are per-day
 * aggregates over the warehouse's history (counters accumulate from deployment day); the UI does
 * the charting and forecasting. {@code day} serializes as {@code YYYY-MM-DD}.
 */
public final class ReportingDtos {

    private ReportingDtos() {
    }

    /** Scan quality at a scan point: scans answered, scanner read errors, unknown barcodes. */
    public record ScanQualityRow(String node, LocalDate day, long scans, long noReads, long unknowns) {
    }

    /** Conveyor traffic over a directed edge (the heatmap's weight). */
    public record TrafficRow(String fromNode, String toNode, LocalDate day, long count) {
    }

    /** Completed storage movements per location (null = task carried no location id). */
    public record StorageMovementRow(UUID locationId, LocalDate day, long stores, long retrieves) {
    }

    /** Device-task throughput + failures per equipment (best available label) per day. */
    public record DeviceMovementRow(String equipment, String family, LocalDate day, long completed,
                                    long failed) {
    }

    /** Induct→arrival transit-time distribution for a day: sample count, p50 and p95 in ms. */
    public record TransitTimeRow(LocalDate day, long count, long p50Ms, long p95Ms) {
    }

    /**
     * Per-scan routing decision latency of THIS instance: percentiles over the last (up to) 4096
     * decisions held in an in-memory ring buffer. Per-replica and reset on restart — a live
     * health gauge for the hard-real-time scan path, not durable history.
     */
    public record DecisionLatencyStats(long count, double p50Ms, double p95Ms, double p99Ms,
                                       double maxMs) {
    }
}
