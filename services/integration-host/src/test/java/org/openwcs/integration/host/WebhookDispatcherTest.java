package org.openwcs.integration.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openwcs.integration.host.client.TxLogClient;
import org.openwcs.integration.host.webhook.WebhookDispatcher;
import org.openwcs.integration.host.webhook.WebhookSubscription;
import org.openwcs.integration.host.webhook.WebhookSubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/** A dispatch pass POSTs each confirmation to the callback and advances the subscription cursor. */
class WebhookDispatcherTest extends AbstractHostIntegrationTest {

    @MockBean
    TxLogClient txLog;

    @Autowired
    WebhookDispatcher dispatcher;

    @Autowired
    WebhookSubscriptionRepository subscriptions;

    @Test
    void deliversConfirmationsAndAdvancesCursor() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            received.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            WebhookSubscription sub = new WebhookSubscription();
            sub.setCallbackUrl("http://localhost:" + server.getAddress().getPort() + "/hook");
            sub.setActive(true);
            UUID id = subscriptions.save(sub).getId();

            when(txLog.feed(eq(0L), anyInt())).thenReturn(List.of(
                    new TxLogClient.TxEvent(5, "ORD-1", "Picked", "2026-06-03T08:00:00Z", "op", Map.of("qty", 2)),
                    new TxLogClient.TxEvent(7, "ORD-1", "Shipped", "2026-06-03T08:05:00Z", "op", Map.of())));

            dispatcher.dispatchOnce();

            assertThat(received.get()).isEqualTo(2);
            assertThat(subscriptions.findById(id).orElseThrow().getCursor()).isEqualTo(7);
        } finally {
            server.stop(0);
        }
    }
}
