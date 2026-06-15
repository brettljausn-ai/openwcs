package org.openwcs.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Notification &amp; alerting service (Dashboards &amp; alerting epic). A periodic, ShedLock-guarded
 * evaluator pulls warehouse KPIs from the sibling services, compares them against the master-data
 * thresholds, and opens / clears {@code alert_event} rows. OPEN and CLEAR transitions are delivered
 * best-effort over email (SMTP) and an optional webhook. The alerts API serves the active alert list
 * and supports supervisor acknowledgement.
 */
@SpringBootApplication
@EnableScheduling
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
