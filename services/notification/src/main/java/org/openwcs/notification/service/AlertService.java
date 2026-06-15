package org.openwcs.notification.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.notification.delivery.AlertDelivery;
import org.openwcs.notification.domain.AlertEvent;
import org.openwcs.notification.repo.AlertEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The alert store: opens / clears / acknowledges {@link AlertEvent} rows and dedupes by
 * {@code (warehouse, area, metric)}. Delivery (email + webhook) is fired on the OPEN and CLEAR
 * transitions only, and isolated so a delivery failure can never roll back the state change.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final List<String> ACTIVE = List.of("OPEN", "ACKED");

    private final AlertEventRepository repo;
    private final List<AlertDelivery> deliveries;

    public AlertService(AlertEventRepository repo, List<AlertDelivery> deliveries) {
        this.repo = repo;
        this.deliveries = deliveries;
    }

    static String dedupeKey(UUID warehouseId, String area, String metric) {
        return warehouseId + "|" + area + "|" + metric;
    }

    /**
     * Record that a metric is over its threshold. If there is no active alert for this
     * (warehouse, area, metric) one is OPENED and delivered. If one already exists it is UPDATED in
     * place (newest value/severity) and NOT re-delivered — this is the dedupe that stops a sustained
     * breach from spamming.
     */
    @Transactional
    public AlertEvent raise(UUID warehouseId, String area, String metric, String severity,
                            BigDecimal value, BigDecimal threshold) {
        String key = dedupeKey(warehouseId, area, metric);
        Optional<AlertEvent> existing = repo.findFirstByDedupeKeyAndStateIn(key, ACTIVE);
        if (existing.isPresent()) {
            AlertEvent a = existing.get();
            a.setSeverity(severity);
            a.setValue(value);
            a.setThreshold(threshold);
            return repo.save(a);
        }
        AlertEvent a = new AlertEvent();
        a.setWarehouseId(warehouseId);
        a.setArea(area);
        a.setMetric(metric);
        a.setSeverity(severity);
        a.setValue(value);
        a.setThreshold(threshold);
        a.setState("OPEN");
        a.setDedupeKey(key);
        a.setOpenedAt(Instant.now());
        AlertEvent saved = repo.save(a);
        log.info("alert OPENED {} {}/{} {} value={} threshold={}",
                warehouseId, area, metric, severity, value, threshold);
        deliver(d -> d.onOpen(saved));
        return saved;
    }

    /**
     * Record that a metric is back under its threshold: any active alert for this
     * (warehouse, area, metric) is CLEARED and a CLEAR delivery fires. No-op if none is active.
     */
    @Transactional
    public void clear(UUID warehouseId, String area, String metric) {
        String key = dedupeKey(warehouseId, area, metric);
        repo.findFirstByDedupeKeyAndStateIn(key, ACTIVE).ifPresent(a -> {
            a.setState("CLEARED");
            a.setClearedAt(Instant.now());
            AlertEvent saved = repo.save(a);
            log.info("alert CLEARED {} {}/{}", warehouseId, area, metric);
            deliver(d -> d.onClear(saved));
        });
    }

    /** Acknowledge an active alert (supervisor action). OPEN → ACKED; idempotent on an ACKED alert. */
    @Transactional
    public AlertEvent acknowledge(UUID id, String actor) {
        AlertEvent a = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
        if ("CLEARED".equals(a.getState())) {
            throw new IllegalStateException("Cannot acknowledge a cleared alert");
        }
        a.setState("ACKED");
        a.setAckedAt(Instant.now());
        a.setAckedBy(actor);
        log.info("alert ACKED {} by {}", id, actor);
        return repo.save(a);
    }

    @Transactional(readOnly = true)
    public List<AlertEvent> activeForWarehouse(UUID warehouseId) {
        return repo.findByWarehouseIdAndStateInOrderByOpenedAtDesc(warehouseId, ACTIVE);
    }

    private void deliver(java.util.function.Consumer<AlertDelivery> action) {
        for (AlertDelivery d : deliveries) {
            try {
                action.accept(d);
            } catch (Exception e) {
                log.warn("delivery channel {} failed: {}", d.getClass().getSimpleName(), e.toString());
            }
        }
    }
}
