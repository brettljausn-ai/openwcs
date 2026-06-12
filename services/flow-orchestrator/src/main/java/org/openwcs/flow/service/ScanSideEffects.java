package org.openwcs.flow.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.openwcs.flow.api.RoutingDtos.RoutingDecision;
import org.openwcs.flow.repo.InductionQueueEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Asynchronous observability side effects of a routing decision, kept OFF the hard-real-time scan
 * thread: the daily scan_stat / edge_traffic counter upserts and the {@code SCANNED} HU-trace row
 * (which first needs an induction-entry lookup). The scan path only enqueues (a lock-free offer);
 * a single background thread works the queue.
 *
 * <p><b>Ordering:</b> one FIFO queue, one worker — side effects persist in decision order, per
 * barcode and globally. <b>Durability:</b> counters and trace rows are observability, not control
 * flow: a failure (or a full queue, or a crash with entries in flight) WARNs and drops; the
 * routing answer is never affected, today's reporting rows merely undercount. The route POSITION
 * is deliberately NOT here — it gates loop-capacity occupancy and plan progression and stays a
 * synchronous write in {@link RoutingService} (see the comment there).
 */
@Service
public class ScanSideEffects {

    private static final Logger log = LoggerFactory.getLogger(ScanSideEffects.class);

    /** Bounded so a stuck DB can never balloon the heap; ~30 s of backlog at 300 scans/s. */
    private static final int QUEUE_CAPACITY = 10_000;

    private final ReportingService reporting;
    private final InductionQueueEntryRepository inductionEntries;
    private final HuTraceService trace;

    private final LinkedBlockingQueue<PendingScan> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong enqueued = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private volatile boolean running = true;
    private Thread worker;

    public ScanSideEffects(ReportingService reporting, InductionQueueEntryRepository inductionEntries,
                           HuTraceService trace) {
        this.reporting = reporting;
        this.inductionEntries = inductionEntries;
        this.trace = trace;
    }

    /** One decision's deferred side effects. {@code barcode} is null on a scanner read error. */
    private record PendingScan(UUID warehouseId, String node, String barcode, boolean noRead,
                               boolean unknownBarcode, RoutingDecision decision) {
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::drain, "flow-scan-side-effects");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    /**
     * Enqueue a decision's side effects (counters + trace). Non-blocking: when the queue is full
     * (the worker can't keep up, e.g. the DB is down) the entry is DROPPED with a WARN — the scan
     * answer must never wait on observability.
     */
    public void enqueue(UUID warehouseId, String node, String barcode, boolean noRead,
                        boolean unknownBarcode, RoutingDecision decision) {
        PendingScan pending = new PendingScan(warehouseId, node, barcode, noRead, unknownBarcode, decision);
        if (queue.offer(pending)) {
            enqueued.incrementAndGet();
        } else {
            log.warn("scan side-effect queue full ({} entries): dropping counters/trace for the scan at "
                    + "node {} (today's scan_stat/edge_traffic undercount; the trace misses one SCANNED "
                    + "row; the scan itself was answered)", QUEUE_CAPACITY, node);
        }
    }

    private void drain() {
        while (running) {
            PendingScan pending;
            try {
                pending = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (pending == null) {
                continue;
            }
            try {
                process(pending);
            } finally {
                finished.incrementAndGet();
            }
        }
    }

    private void process(PendingScan p) {
        // Reporting counters: atomic upserts in their own transaction (REQUIRES_NEW inside).
        try {
            String routedTo = "ROUTE".equals(p.decision().action()) ? p.decision().toNode() : null;
            reporting.countDecision(p.warehouseId(), p.node(), p.noRead(), p.unknownBarcode(), routedTo);
        } catch (Throwable t) {
            log.warn("reporting counters failed for the scan at node {} (dropped; today's "
                    + "scan_stat/edge_traffic rows undercount): {}", p.node(), t.toString());
        }
        // SCANNED trace row, only for barcodes that belong to a live transport (ADR-0008 §3).
        if (p.noRead()) {
            return; // nothing to trace: no HU identity
        }
        try {
            inductionEntries
                    .findFirstByWarehouseIdAndHuCodeOrderByRequestedAtDesc(p.warehouseId(), p.barcode())
                    .ifPresent(entry -> trace.record(p.warehouseId(), entry.getHuId(), entry.getHuCode(),
                            p.node(), "SCANNED", describe(p.decision()), null, p.decision().toNode(),
                            entry.getWorkplaceId(), null, entry.getId()));
        } catch (Throwable t) {
            log.warn("scan trace failed (dropped; the HU timeline misses one SCANNED row) for {} at {}: {}",
                    p.barcode(), p.node(), t.toString());
        }
    }

    /** A short human string of the routing answer for the trace's {@code decision} column. */
    private static String describe(RoutingDecision d) {
        return switch (d.action()) {
            case "ROUTE" -> "routed to " + d.toNode() + " via " + d.exitCode();
            case "HOLD" -> d.detail() == null || d.detail().isBlank() ? "held" : "held: " + d.detail();
            case "COMPLETE" -> "destination reached";
            case "NO_ROUTE" -> "no route";
            default -> "exception: " + d.detail();
        };
    }

    /**
     * Test hook: block until everything enqueued so far has been worked (NOT part of the scan
     * path). Lets tests assert "eventually persisted" deterministically.
     */
    public void awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long target = enqueued.get();
        while (finished.get() < target) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("scan side effects not drained within " + timeout);
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }
}
