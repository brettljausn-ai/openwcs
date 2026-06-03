package org.openwcs.gtp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Goods-to-person (GTP) station execution service (ADR 0006): configure GTP workplaces and
 * their stock/order nodes, present a stock HU against open per-destination demand to generate
 * a put-list (put-to-light), confirm puts, and complete order destinations. Supports both
 * ORDER_LOCATION (HU-in-location/conveyor) and PUT_WALL (lit rack cubbies, typical AMR) modes.
 */
@SpringBootApplication
public class GtpApplication {
    public static void main(String[] args) {
        SpringApplication.run(GtpApplication.class, args);
    }
}
