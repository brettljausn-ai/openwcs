package org.openwcs.slotting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Slotting &amp; replenishment service (ADR 0003): put-away location assignment for automated
 * rack / GTP blocks, fixed pick-face slotting + min/max replenishment, and off-peak re-slotting.
 * Scheduling is enabled for the opportunistic replenishment + re-slot jobs (PR3/PR4).
 */
@SpringBootApplication
@EnableScheduling
public class SlottingApplication {
    public static void main(String[] args) {
        SpringApplication.run(SlottingApplication.class, args);
    }
}
