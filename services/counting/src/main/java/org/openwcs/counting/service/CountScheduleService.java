package org.openwcs.counting.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openwcs.counting.api.NotFoundException;
import org.openwcs.counting.domain.CountSchedule;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountScheduleRepository;
import org.openwcs.counting.repo.CountTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ABC-cadence scheduling: stores {@link CountSchedule}s and emits the count tasks that are due. A
 * schedule with a short cadence (A SKUs/fast zones) comes due often; a long cadence (C SKUs) rarely.
 * Generating a due task advances {@code nextDueAt} by {@code cadenceDays} so the cadence repeats.
 */
@Service
public class CountScheduleService {

    private static final Logger log = LoggerFactory.getLogger(CountScheduleService.class);

    private final CountScheduleRepository schedules;
    private final CountTaskRepository tasks;

    public CountScheduleService(CountScheduleRepository schedules, CountTaskRepository tasks) {
        this.schedules = schedules;
        this.tasks = tasks;
    }

    @Transactional
    public CountSchedule create(CountSchedule schedule) {
        if (schedule.getWarehouseId() == null) {
            throw new IllegalArgumentException("warehouseId is required");
        }
        if (schedule.getCadenceDays() <= 0) {
            throw new IllegalArgumentException("cadenceDays must be positive");
        }
        if (schedule.getNextDueAt() == null) {
            schedule.setNextDueAt(Instant.now());
        }
        return schedules.save(schedule);
    }

    public CountSchedule get(UUID id) {
        return schedules.findById(id)
                .orElseThrow(() -> new NotFoundException("count schedule not found: " + id));
    }

    public List<CountSchedule> list(UUID warehouseId) {
        return schedules.findByWarehouseId(warehouseId);
    }

    /**
     * Emit a CountTask for every active schedule whose next-due time has passed (optionally scoped to
     * one warehouse). Each emitted task carries the schedule's scope + count type; the schedule's
     * {@code nextDueAt} is advanced by its cadence. Returns the tasks created.
     */
    @Transactional
    public List<CountTask> generateDueTasks(UUID warehouseId, Instant now) {
        List<CountSchedule> due = warehouseId == null
                ? schedules.findByStatusAndNextDueAtLessThanEqual("ACTIVE", now)
                : schedules.findByWarehouseIdAndStatusAndNextDueAtLessThanEqual(warehouseId, "ACTIVE", now);
        List<CountTask> created = new ArrayList<>();
        for (CountSchedule schedule : due) {
            CountTask task = new CountTask();
            task.setWarehouseId(schedule.getWarehouseId());
            task.setScopeType(scopeTypeForTask(schedule));
            task.setScopeRef(schedule.getScopeRef());
            task.setCountType(schedule.getCountType());
            task.setOrigin("SCHEDULED");
            task.setScheduleId(schedule.getId());
            task.setTolerance(schedule.getTolerance() == null ? BigDecimal.ZERO : schedule.getTolerance());
            task.setStatus("OPEN");
            created.add(tasks.save(task));

            schedule.setLastRunAt(now);
            schedule.setNextDueAt(now.plus(Duration.ofDays(schedule.getCadenceDays())));
            CountTask emitted = created.get(created.size() - 1);
            log.info("schedule {} ({}) came due and emitted count task {} (scope {} {}, type {}); "
                            + "next due {} (cadence {} day(s))",
                    schedule.getId(), schedule.getName(), emitted.getId(), emitted.getScopeType(),
                    emitted.getScopeRef(), emitted.getCountType(), schedule.getNextDueAt(),
                    schedule.getCadenceDays());
        }
        return created;
    }

    /** An ABC_CLASS schedule produces a SKU-class sweep; otherwise the scope type carries through. */
    private static String scopeTypeForTask(CountSchedule schedule) {
        return "ABC_CLASS".equals(schedule.getScopeType()) ? "ZONE" : schedule.getScopeType();
    }
}
