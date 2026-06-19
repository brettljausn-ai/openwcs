package org.openwcs.counting.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background reclaim of stale count-line reservations. On its interval it frees reservations that
 * were taken but never started counting and have aged past the TTL (an operator claimed a line then
 * walked away or lost the handheld). Started lines are never touched. Replica-safe via ShedLock (one
 * replica per fire). The {@code initialDelay} keeps it from firing during the sub-minute Spring
 * tests; the tests drive {@link LineReservationService#sweepStale()} directly.
 */
@Component
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);

    private final LineReservationService reservations;

    public ReservationSweeper(LineReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${openwcs.counting.reservation-sweep-interval:300000}",
            initialDelayString = "${openwcs.counting.reservation-sweep-interval:300000}")
    @SchedulerLock(name = "counting-reservation-sweep")
    public void sweep() {
        try {
            reservations.sweepStale();
        } catch (Throwable t) {
            // The sweep must never crash the scheduler; log and try again next interval.
            log.warn("reservation sweep failed unexpectedly: {}", t.toString());
        }
    }
}
