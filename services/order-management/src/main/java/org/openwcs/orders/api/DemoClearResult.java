package org.openwcs.orders.api;

/**
 * Counts of transactional order rows removed by a demo full-reset clear (build.md §4.8).
 * {@code orders} cascades to its lines and line transactions; {@code outboxMessages} is the
 * drained order outbox. Infrastructure / master-data references are untouched.
 */
public record DemoClearResult(int orders, long outboxMessages) {}
