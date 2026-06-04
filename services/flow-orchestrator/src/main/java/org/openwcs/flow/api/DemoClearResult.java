package org.openwcs.flow.api;

/**
 * Counts of transactional flow-orchestrator rows removed by a demo full-reset clear
 * (build.md §4.8). Topology configuration (conveyor nodes, edges, loops, controllers) is
 * untouched; only the per-warehouse operational state — device tasks, handling-unit routes
 * and topology observations — is purged.
 */
public record DemoClearResult(int deviceTasks, int huRoutes, int topologyObservations) {}
