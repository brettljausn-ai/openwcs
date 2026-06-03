package org.openwcs.counting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cycle / stock counting service: scheduled (ABC-cadence) and ad-hoc counts over a scope
 * (location / SKU / zone / block), blind vs variance capture, and reconciliation against the
 * inventory-expected snapshot. On approval an adjustment is posted to inventory via the
 * transaction log ({@code StockAdjusted}); out-of-tolerance variances spawn a recount task.
 * Scheduling is enabled for the ABC-cadence sweep that emits due count tasks. REST under
 * {@code /api/counting/**}.
 */
@SpringBootApplication
@EnableScheduling
public class CountingApplication {
    public static void main(String[] args) {
        SpringApplication.run(CountingApplication.class, args);
    }
}
