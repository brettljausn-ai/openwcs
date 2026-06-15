package org.openwcs.notification.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the {@link AlertEvaluator} on a fixed interval (default 60s,
 * {@code openwcs.notification.evaluate-interval-ms}). {@code @SchedulerLock} ensures only one replica
 * runs each tick when the service is scaled out (see ShedLockConfig).
 *
 * <p>Gated on {@code openwcs.notification.scheduler.enabled} (mirrors inventory's density-sweep
 * gate): enabled in production via application.yml, absent in tests so a background evaluation tick
 * never races the tests' mocked metric sources.
 */
@Component
@ConditionalOnProperty(name = "openwcs.notification.scheduler.enabled", havingValue = "true",
        matchIfMissing = false)
public class AlertEvaluatorScheduler {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluatorScheduler.class);

    private final AlertEvaluator evaluator;

    public AlertEvaluatorScheduler(AlertEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @Scheduled(fixedDelayString = "${openwcs.notification.evaluate-interval-ms:60000}")
    @SchedulerLock(name = "notification-alert-evaluator", lockAtMostFor = "PT5M")
    public void evaluate() {
        log.debug("running alert evaluation tick");
        evaluator.evaluateAll();
    }
}
