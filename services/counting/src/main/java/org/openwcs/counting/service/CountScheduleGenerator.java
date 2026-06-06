package org.openwcs.counting.service;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Off-peak ABC-cadence sweep: on the configured cron ({@code openwcs.counting.schedule-cron}) every
 * active schedule that has come due emits a count task. Cron-only so it never fires during tests with
 * the default schedule; a manual sweep is also exposed via the schedule API.
 */
@Component
public class CountScheduleGenerator {

    private static final Logger log = LoggerFactory.getLogger(CountScheduleGenerator.class);

    private final CountScheduleService schedules;

    public CountScheduleGenerator(CountScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(cron = "${openwcs.counting.schedule-cron:0 0 1 * * *}")
    @SchedulerLock(name = "counting-schedule-sweep")
    public void sweep() {
        int created = schedules.generateDueTasks(null, Instant.now()).size();
        if (created > 0) {
            log.info("ABC-cadence sweep emitted {} due count task(s)", created);
        }
    }
}
