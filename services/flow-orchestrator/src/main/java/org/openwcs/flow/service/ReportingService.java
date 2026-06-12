package org.openwcs.flow.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.openwcs.flow.api.ReportingDtos.DeviceMovementRow;
import org.openwcs.flow.api.ReportingDtos.ScanQualityRow;
import org.openwcs.flow.api.ReportingDtos.StorageMovementRow;
import org.openwcs.flow.api.ReportingDtos.TrafficRow;
import org.openwcs.flow.api.ReportingDtos.TransitTimeRow;
import org.openwcs.flow.repo.DeviceTaskRepository;
import org.openwcs.flow.repo.EdgeTrafficRepository;
import org.openwcs.flow.repo.HuTransportTraceRepository;
import org.openwcs.flow.repo.ScanStatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reporting (build.md Reporting screens): daily scan-quality and edge-traffic counters fed by the
 * routing scan path, plus per-day aggregates computed over existing data (device tasks, the HU
 * transport trace). Counters are single atomic upserts per scan and are fully isolated: a counter
 * failure must NEVER break the routing answer. History accumulates from deployment day; the UI
 * reads it and does the forecasting.
 */
@Service
public class ReportingService {

    /** Longest history window an endpoint serves (and the default when none is given). */
    static final int MAX_DAYS = 180;
    static final int DEFAULT_DAYS = 90;

    private final ScanStatRepository scanStats;
    private final EdgeTrafficRepository edgeTraffic;
    private final DeviceTaskRepository deviceTasks;
    private final HuTransportTraceRepository traces;

    public ReportingService(ScanStatRepository scanStats, EdgeTrafficRepository edgeTraffic,
                            DeviceTaskRepository deviceTasks, HuTransportTraceRepository traces) {
        this.scanStats = scanStats;
        this.edgeTraffic = edgeTraffic;
        this.deviceTasks = deviceTasks;
        this.traces = traces;
    }

    // ------------------------------------------------------------------ counters (scan path)

    /**
     * Count one answered scan at a scan point in today's daily rows: every scan bumps
     * {@code scans} ({@code noRead} marks a scanner read error, {@code unknown} a readable barcode
     * with no active route plan), and a non-null {@code routedToNode} additionally bumps the
     * traffic counter of the answered edge. Atomic upserts, in their OWN transaction
     * (REQUIRES_NEW): a failing counter statement marks only THIS transaction rollback-only, so
     * the caller's catch keeps the scan transaction (and its answer) intact.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void countDecision(UUID warehouseId, String node, boolean noRead, boolean unknown,
                              String routedToNode) {
        scanStats.bump(warehouseId, node, noRead ? 1 : 0, unknown ? 1 : 0);
        if (routedToNode != null) {
            edgeTraffic.bump(warehouseId, node, routedToNode);
        }
    }

    // ------------------------------------------------------------------------- report reads

    /** Scans / no-reads / unknowns per scan point per day, last {@code days} days. */
    @Transactional(readOnly = true)
    public List<ScanQualityRow> scanQuality(UUID warehouseId, int days) {
        return scanStats.quality(warehouseId, clampDays(days)).stream()
                .map(r -> new ScanQualityRow(r.getNode(), r.getDay().toLocalDate(), r.getScans(),
                        r.getNoReads(), r.getUnknowns()))
                .toList();
    }

    /** ROUTE answers per directed edge per day (traffic heatmap), last {@code days} days. */
    @Transactional(readOnly = true)
    public List<TrafficRow> traffic(UUID warehouseId, int days) {
        return edgeTraffic.traffic(warehouseId, clampDays(days)).stream()
                .map(r -> new TrafficRow(r.getFromNode(), r.getToNode(), r.getDay().toLocalDate(),
                        r.getCount()))
                .toList();
    }

    /** Completed STORE/RETRIEVE movements per storage location per day, last {@code days} days. */
    @Transactional(readOnly = true)
    public List<StorageMovementRow> storageMovements(UUID warehouseId, int days) {
        return deviceTasks.storageMovements(warehouseId, clampDays(days)).stream()
                .map(r -> new StorageMovementRow(r.getLocationId(), r.getDay().toLocalDate(),
                        r.getStores(), r.getRetrieves()))
                .toList();
    }

    /** Device-task throughput + failures per equipment per day, last {@code days} days. */
    @Transactional(readOnly = true)
    public List<DeviceMovementRow> deviceMovements(UUID warehouseId, int days) {
        return deviceTasks.deviceMovements(warehouseId, clampDays(days)).stream()
                .map(r -> new DeviceMovementRow(r.getEquipment(), r.getFamily(),
                        r.getDay().toLocalDate(), r.getCompleted(), r.getFailed()))
                .toList();
    }

    /**
     * Induct→arrival transit-time distribution per day (count, p50, p95 in ms), last {@code days}
     * days. The percentiles are computed in Java over the day's samples (one sample per induction
     * entry), which is fine at daily-report scale.
     */
    @Transactional(readOnly = true)
    public List<TransitTimeRow> transitTimes(UUID warehouseId, int days) {
        Map<LocalDate, List<Long>> samplesByDay = new TreeMap<>();
        for (HuTransportTraceRepository.TransitSampleAgg sample
                : traces.transitSamples(warehouseId, clampDays(days))) {
            samplesByDay.computeIfAbsent(sample.getDay().toLocalDate(), d -> new ArrayList<>())
                    .add(sample.getMs());
        }
        List<TransitTimeRow> rows = new ArrayList<>(samplesByDay.size());
        samplesByDay.forEach((day, samples) -> {
            samples.sort(null);
            rows.add(new TransitTimeRow(day, samples.size(),
                    percentile(samples, 50), percentile(samples, 95)));
        });
        return rows;
    }

    /** Nearest-rank percentile over an ascending-sorted, non-empty sample list. */
    private static long percentile(List<Long> sorted, int pct) {
        int rank = (int) Math.ceil(pct / 100.0 * sorted.size());
        return sorted.get(Math.max(0, rank - 1));
    }

    /** Serve at most {@link #MAX_DAYS} days of history; nonsense values fall back to sane ones. */
    static int clampDays(int days) {
        return days < 1 ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
    }
}
