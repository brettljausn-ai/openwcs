package org.openwcs.flow.api;

/**
 * Result of the flow-orchestrator demo dashboard seed: how many scan-stat nodes were bumped for
 * today and whether a placed equipment was marked faulted, to light up /reports/automation-summary.
 */
public record DemoDashboardSeedResult(int scanNodes, int scansBumped, int faultedEquipment) {
}
