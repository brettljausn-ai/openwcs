package org.openwcs.counting.service;

import java.util.List;
import org.openwcs.counting.domain.CountTask;
import org.openwcs.counting.repo.CountTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background retry for ASRS count-tote routing. Every minute it re-attempts routing for OPEN count
 * tasks whose routing is still PENDING (never attempted) or FAILED (a transient failure, e.g. the
 * emulator was off or a downstream service was down). {@link CountRoutingService#routeTask} is
 * idempotent, so re-routing an already-routed line never duplicates a transport.
 *
 * <p>Replica-safe via ShedLock (a single replica runs each sweep). The {@code initialDelay} of one
 * minute keeps it from firing during the sub-minute Spring tests.
 */
@Component
public class CountRoutingScheduler {

    private static final Logger log = LoggerFactory.getLogger(CountRoutingScheduler.class);
    private static final List<String> RETRYABLE = List.of("PENDING", "FAILED");

    private final CountTaskRepository tasks;
    private final CountRoutingService routing;

    public CountRoutingScheduler(CountTaskRepository tasks, CountRoutingService routing) {
        this.tasks = tasks;
        this.routing = routing;
    }

    @Scheduled(fixedDelayString = "${openwcs.counting.routing-retry-ms:60000}",
            initialDelayString = "${openwcs.counting.routing-retry-ms:60000}")
    @SchedulerLock(name = "counting-routing-retry")
    public void retry() {
        List<CountTask> due = tasks.findByStatusAndRoutingStatusIn("OPEN", RETRYABLE);
        if (due.isEmpty()) {
            return;
        }
        int attempted = 0;
        for (CountTask task : due) {
            try {
                routing.routeTask(task);
                attempted++;
            } catch (Throwable t) {
                // routeTask is meant to never throw, but a bad task must never stop the sweep.
                log.warn("count task {} routing retry failed unexpectedly: {}", task.getId(), t.toString());
            }
        }
        log.info("ASRS count-routing retry swept {} task(s)", attempted);
    }
}
