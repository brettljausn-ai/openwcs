package org.openwcs.orders.service;

import java.time.Instant;
import java.util.UUID;
import org.openwcs.orders.domain.OutboundOrder;
import org.openwcs.orders.domain.RouteDispatch;
import org.openwcs.orders.domain.RouteDispatchStatus;
import org.openwcs.orders.repo.OutboundOrderRepository;
import org.openwcs.orders.repo.RouteDispatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains the persisted {@link RouteDispatch} waves that back the dispatch board. A wave is
 * lazily opened when an outbound order is first assigned to a (route, cut-off), then advanced as
 * its orders progress:
 *
 * <ul>
 *   <li><b>OPEN</b> — orders assigned, none worked yet.</li>
 *   <li><b>LOADING</b> — the first assigned order became ready (ALLOCATED / PARTIALLY_ALLOCATED)
 *       or shipped; {@code firstLoadedAt} is stamped on entry.</li>
 *   <li><b>DEPARTED</b> — every assigned order has shipped; {@code departedAt} is stamped on
 *       entry (the actual departure time the SLA report uses).</li>
 * </ul>
 *
 * <p>Every method is <b>best-effort</b>: it runs inside the caller's transaction (so updates
 * commit atomically with the order change) but a failure here is swallowed and logged — a wave
 * bookkeeping problem must never break order create, release or shipping. Counts and status are
 * recomputed from the orders themselves on each call, so the wave is self-correcting and order
 * of events does not matter. Waves are only opened for orders that carry both a route and a
 * cut-off (the board groups on that pair); orders missing either are not represented, exactly as
 * the old derived board ignored null routes/cut-offs in its next-departure logic.
 */
@Service
public class RouteDispatchService {

    private static final Logger log = LoggerFactory.getLogger(RouteDispatchService.class);

    private final RouteDispatchRepository dispatches;
    private final OutboundOrderRepository orders;

    public RouteDispatchService(RouteDispatchRepository dispatches, OutboundOrderRepository orders) {
        this.dispatches = dispatches;
        this.orders = orders;
    }

    /**
     * Reflect one outbound order onto its dispatch wave: lazily open the wave for its
     * (warehouse, route, cut-off) and recompute counts + status. No-op when the order carries no
     * route or cut-off. Best-effort.
     */
    public void onOrderChanged(OutboundOrder order) {
        if (order == null || order.getRouteCode() == null || order.getDispatchBy() == null) {
            return;
        }
        try {
            UUID wh = order.getWarehouseId();
            String route = order.getRouteCode();
            Instant cutoff = order.getDispatchBy();
            // Flush the order's own insert/status change first so the roll-up query below sees it
            // (it counts orders straight from the DB, so the just-changed order must be visible).
            orders.flush();
            RouteDispatch wave = dispatches
                    .findByWarehouseIdAndRouteCodeAndCutoff(wh, route, cutoff)
                    .orElseGet(() -> dispatches.save(new RouteDispatch(wh, route, cutoff)));
            recompute(wave);
        } catch (RuntimeException e) {
            // Best-effort: never let dispatch bookkeeping break the order lifecycle.
            log.warn("route_dispatch update for order {} (route {}, cut-off {}) failed, skipping: {}",
                    order.getOrderRef(), order.getRouteCode(), order.getDispatchBy(), e.getMessage());
        }
    }

    /** Recompute one wave's counts + status from the current orders on its (route, cut-off). */
    private void recompute(RouteDispatch wave) {
        OutboundOrderRepository.RouteDispatchRollup rollup = orders.routeDispatchRollup(
                wave.getWarehouseId(), wave.getRouteCode(), wave.getCutoff());
        long assigned = rollup.getAssigned();
        long ready = rollup.getReady();
        long shipped = rollup.getShipped();
        wave.setOrdersAssigned((int) assigned);
        wave.setOrdersReady((int) ready);

        if (assigned > 0 && shipped >= assigned) {
            if (wave.getStatus() != RouteDispatchStatus.DEPARTED) {
                if (wave.getFirstLoadedAt() == null) {
                    wave.setFirstLoadedAt(Instant.now());
                }
                wave.setStatus(RouteDispatchStatus.DEPARTED);
                wave.setDepartedAt(Instant.now());
                log.info("route_dispatch {} (route {}) DEPARTED: all {} assigned orders shipped",
                        wave.getId(), wave.getRouteCode(), assigned);
            }
        } else if (ready > 0) {
            if (wave.getStatus() == RouteDispatchStatus.OPEN) {
                wave.setStatus(RouteDispatchStatus.LOADING);
                if (wave.getFirstLoadedAt() == null) {
                    wave.setFirstLoadedAt(Instant.now());
                }
                log.info("route_dispatch {} (route {}) LOADING: first of {} assigned orders ready",
                        wave.getId(), wave.getRouteCode(), assigned);
            }
        }
        // Note: status never regresses (e.g. a re-opened/cancelled order does not pull a DEPARTED
        // wave back); the board only ever moves forward, matching operational expectations.
    }
}
