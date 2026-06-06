package org.openwcs.integration.host.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically pushes new confirmations to registered webhooks. Active only when
 * {@code openwcs.host.webhook.enabled=true} (on in compose; off in dev/test so the pull feed is
 * used and tests drive {@link WebhookDispatcher#dispatchOnce()} directly).
 */
@Component
@ConditionalOnProperty(name = "openwcs.host.webhook.enabled", havingValue = "true")
public class WebhookScheduler {

    private final WebhookDispatcher dispatcher;

    public WebhookScheduler(WebhookDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${openwcs.host.webhook.interval-ms:5000}")
    @SchedulerLock(name = "host-webhook-dispatch", lockAtMostFor = "PT1M")
    public void run() {
        dispatcher.dispatchOnce();
    }
}
