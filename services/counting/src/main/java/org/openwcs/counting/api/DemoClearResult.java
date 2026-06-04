package org.openwcs.counting.api;

/**
 * Counts of transactional counting rows removed by a demo full-reset clear (build.md §4.8).
 * Deleting a count task cascades to its {@code countLines}. Count schedules (configuration)
 * are kept.
 */
public record DemoClearResult(int countTasks, int countLines) {}
