package org.openwcs.integration.host.api;

import java.util.List;
import java.util.Map;
import org.openwcs.integration.host.client.TxLogClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Confirmations back to the host as a cursor feed (pull). The host polls with its last
 * {@code cursor}; openWCS returns events since then (from the transaction log) and the
 * {@code nextCursor} to use on the following call. Webhook push is a planned follow-up.
 */
@RestController
@RequestMapping("/api/host")
public class ConfirmationController {

    private final TxLogClient txLog;

    public ConfirmationController(TxLogClient txLog) {
        this.txLog = txLog;
    }

    @GetMapping("/confirmations")
    public ConfirmationPage confirmations(
            @RequestParam(defaultValue = "0") long cursor,
            @RequestParam(defaultValue = "100") int limit) {
        List<TxLogClient.TxEvent> events = txLog.feed(cursor, limit);
        List<Confirmation> confirmations = events.stream()
                .map(e -> new Confirmation(e.position(), e.eventType(), e.streamId(), e.occurredAt(),
                        e.actor(), e.payload()))
                .toList();
        long nextCursor = events.isEmpty() ? cursor : events.get(events.size() - 1).position();
        return new ConfirmationPage(confirmations, nextCursor);
    }

    /** One confirmation event for the host; {@code cursor} is this event's global position. */
    public record Confirmation(long cursor, String type, String reference, String occurredAt,
                               String actor, Map<String, Object> payload) {
    }

    public record ConfirmationPage(List<Confirmation> confirmations, long nextCursor) {
    }
}
