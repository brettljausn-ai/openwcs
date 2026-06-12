package org.openwcs.counting.service;

import java.util.UUID;
import org.openwcs.counting.api.DemoClearResult;
import org.openwcs.counting.repo.CountLineRepository;
import org.openwcs.counting.repo.CountTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Demo mode for the counting service (build.md §4.8). When demo mode is turned off the
 * operational state is reset: every count task for the warehouse is deleted (its count lines
 * cascade at the DB level via {@code count_line.count_task_id ON DELETE CASCADE}). Count
 * schedules are configuration and are deliberately kept.
 */
@Service
public class DemoResetService {

    private final CountTaskRepository tasks;
    private final CountLineRepository lines;

    public DemoResetService(CountTaskRepository tasks, CountLineRepository lines) {
        this.tasks = tasks;
        this.lines = lines;
    }

    @Transactional
    public DemoClearResult clear(UUID warehouseId) {
        // Count first, then one bulk DELETE; lines cascade at the DB level. Never loads
        // the (potentially large) task/line sets into memory.
        long linesRemoved = lines.countByWarehouseId(warehouseId);
        int tasksRemoved = tasks.deleteBulkByWarehouseId(warehouseId);
        return new DemoClearResult(tasksRemoved, (int) linesRemoved);
    }
}
