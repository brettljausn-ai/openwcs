package org.openwcs.orders.api;

import java.util.List;

/**
 * Dispatch board derived purely from open outbound orders grouped by their {@code routeCode}
 * and {@code dispatchBy} cut-off. No dispatch/ops entity exists, so every figure is computed
 * from the order + route data already on the orders. Read-only, warehouse-scoped, best-effort.
 *
 * <ul>
 *   <li><b>nextDeparture</b>: the soonest still-open route cut-off in the future, or
 *       {@code null} if no open outbound order carries a cut-off.</li>
 *   <li><b>routes</b>: one row per (routeCode, cut-off) group of open outbound orders,
 *       soonest cut-off first.</li>
 *   <li><b>ordersAssigned</b>: open outbound orders in the group.</li>
 *   <li><b>ordersReady</b>: the assigned subset in a ready state (ALLOCATED or SHIPPED).</li>
 *   <li><b>status</b>: best-effort group state — {@code departed} when every order shipped,
 *       {@code loading} when at least one is ready/allocated, else {@code open}.</li>
 * </ul>
 */
public record DispatchReport(NextDeparture nextDeparture, List<Route> routes) {

    /** The soonest upcoming route cut-off across open outbound orders. */
    public record NextDeparture(String routeCode, String cutoffIso, long secondsToCutoff) {
    }

    /** One (routeCode, cut-off) group of open outbound orders. */
    public record Route(
            String routeCode,
            String cutoffIso,
            long ordersAssigned,
            long ordersReady,
            String status) {
    }
}
