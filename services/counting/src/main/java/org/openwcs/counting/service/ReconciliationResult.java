package org.openwcs.counting.service;

import java.util.UUID;

/**
 * Outcome of reconciling a count task: how many lines auto-approved, how many drove an inventory
 * adjustment, how many were flagged for recount, the task's resulting status, and (if any line
 * needed a recount) the id of the spawned follow-up task.
 */
public record ReconciliationResult(
        UUID taskId,
        String status,
        int approvedLines,
        int adjustedLines,
        int recountLines,
        UUID recountTaskId) {
}
