package org.openwcs.slotting.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Stale-reservation sweeper: on a fixed interval (default ~5m) frees replenishment reservations the
 * operator took but never committed to (started_at IS NULL) older than the TTL
 * ({@code openwcs.slotting.reservation-ttl}, default 15m), so an abandoned claim does not strand a
 * task forever. ShedLock-guarded so it runs on one replica per fire when scaled out.
 */
@Component
public class ReplenishmentReservationSweeper {

    private final ReplenishmentReservationService reservations;

    public ReplenishmentReservationSweeper(ReplenishmentReservationService reservations) {
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${openwcs.slotting.reservation-sweep-interval:300000}")
    @SchedulerLock(name = "slotting-replenishment-reservation-sweep")
    public void sweep() {
        reservations.sweepStale();
    }
}
