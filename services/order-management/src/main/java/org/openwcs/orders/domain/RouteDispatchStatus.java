package org.openwcs.orders.domain;

/**
 * Lifecycle of a persisted route dispatch wave (build.md §4.6 dispatch board).
 * OPEN (orders assigned, none worked) -> LOADING (first assigned order ready/picked) ->
 * DEPARTED (all assigned orders shipped).
 */
public enum RouteDispatchStatus {
    OPEN,
    LOADING,
    DEPARTED
}
