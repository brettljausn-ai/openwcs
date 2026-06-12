package org.openwcs.integration.host.webhook;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manage webhook subscriptions for pushed confirmations on the canonical Host API. */
@RestController
@RequestMapping("/api/host/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final WebhookSubscriptionRepository subscriptions;

    public WebhookController(WebhookSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping
    public SubscriptionView register(@jakarta.validation.Valid @RequestBody RegisterWebhook request) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setCallbackUrl(request.callbackUrl());
        sub.setActive(true);
        WebhookSubscription saved = subscriptions.save(sub);
        log.info("webhook subscription {} registered for {}: confirmations will be pushed from cursor {}",
                saved.getId(), saved.getCallbackUrl(), saved.getCursor());
        return SubscriptionView.from(saved);
    }

    @GetMapping
    public List<SubscriptionView> list() {
        return subscriptions.findAll().stream().map(SubscriptionView::from).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        subscriptions.findById(id).ifPresent(sub -> {
            sub.setActive(false);
            subscriptions.save(sub);
            log.info("webhook subscription {} for {} deactivated: confirmations stop pushing, cursor kept at {}",
                    sub.getId(), sub.getCallbackUrl(), sub.getCursor());
        });
        return ResponseEntity.noContent().build();
    }

    public record RegisterWebhook(@NotBlank String callbackUrl) {
    }

    public record SubscriptionView(UUID id, String callbackUrl, long cursor, boolean active) {
        static SubscriptionView from(WebhookSubscription s) {
            return new SubscriptionView(s.getId(), s.getCallbackUrl(), s.getCursor(), s.isActive());
        }
    }
}
