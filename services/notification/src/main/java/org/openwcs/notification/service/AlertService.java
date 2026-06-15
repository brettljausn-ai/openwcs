package org.openwcs.notification.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.openwcs.notification.api.AlertHealthView;
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

    private static final int MAX_CHATTERING = 10;
    private static final int MAX_STALE = 20;

    /**
     * Alert-system-health snapshot (ISA-18.2 "measure the alarm system"). Optionally scoped to one
     * warehouse ({@code null} = all). The window is the last {@code days} UTC days; an alert is "stale"
     * when it has stayed OPEN longer than {@code staleMinutes}.
     *
     * <p>Definitions:
     * <ul>
     *   <li><b>activeBySeverity</b> — current count of OPEN or ACKED alerts per severity.</li>
     *   <li><b>perDay</b> — zero-filled last {@code days} days; {@code opened} counts rows whose
     *       {@code opened_at} falls on that UTC day, {@code cleared} counts rows whose
     *       {@code cleared_at} falls on it.</li>
     *   <li><b>chattering</b> — (area, metric) keys with two or more CLEARED rows in the window; one
     *       CLEARED row is one flap (an open→clear cycle). Most flaps first, top {@value #MAX_CHATTERING}.</li>
     *   <li><b>stale</b> — alerts still OPEN (never ACKED/CLEARED) whose age exceeds
     *       {@code staleMinutes}; oldest first, top {@value #MAX_STALE}.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public AlertHealthView health(UUID warehouseId, int days, long staleMinutes) {
        int span = Math.max(1, days);
        Instant now = Instant.now();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        // Window start: midnight UTC of the oldest day in the range, inclusive.
        LocalDate firstDay = today.minusDays(span - 1L);
        Instant since = firstDay.atStartOfDay(ZoneOffset.UTC).toInstant();

        // activeBySeverity
        long warning = 0;
        long critical = 0;
        for (var r : repo.activeBySeverity(warehouseId)) {
            if ("WARNING".equals(r.getSeverity())) {
                warning = r.getCnt();
            } else if ("CRITICAL".equals(r.getSeverity())) {
                critical = r.getCnt();
            }
        }

        // perDay, zero-filled across the full range
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < span; i++) {
            byDay.put(firstDay.plusDays(i), new long[2]);
        }
        for (var r : repo.openedPerDay(warehouseId, since)) {
            long[] slot = byDay.get(r.getDay());
            if (slot != null) {
                slot[0] = r.getCnt();
            }
        }
        for (var r : repo.clearedPerDay(warehouseId, since)) {
            long[] slot = byDay.get(r.getDay());
            if (slot != null) {
                slot[1] = r.getCnt();
            }
        }
        List<AlertHealthView.PerDay> perDay = new ArrayList<>(span);
        byDay.forEach((day, c) ->
                perDay.add(new AlertHealthView.PerDay(day.toString(), c[0], c[1])));

        // chattering (window = since), top N
        List<AlertHealthView.Chattering> chattering = repo.chattering(warehouseId, since).stream()
                .limit(MAX_CHATTERING)
                .map(r -> new AlertHealthView.Chattering(r.getArea(), r.getMetric(), r.getFlaps()))
                .toList();

        // stale: still OPEN past the staleness threshold, oldest first, top N
        Instant staleBefore = now.minus(Duration.ofMinutes(staleMinutes));
        List<AlertHealthView.Stale> stale = repo.stale(warehouseId, staleBefore).stream()
                .limit(MAX_STALE)
                .map(r -> new AlertHealthView.Stale(
                        r.getId(), r.getArea(), r.getMetric(),
                        Duration.between(r.getOpenedAt(), now).toMinutes()))
                .toList();

        return new AlertHealthView(
                new AlertHealthView.ActiveBySeverity(warning, critical), perDay, chattering, stale);
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
