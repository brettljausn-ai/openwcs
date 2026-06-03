package org.openwcs.integration.host.client;

import java.util.List;
import java.util.Map;

/** Port to the transaction log for streaming confirmations back to the host (cursor feed). */
public interface TxLogClient {

    /** Events in global position order after {@code afterPosition} (the host's cursor). */
    List<TxEvent> feed(long afterPosition, int limit);

    /** Append an event to the log (e.g. a host-driven StockAdjusted). Returns its identifiers. */
    Appended append(String streamId, String eventType, String actor, Map<String, Object> payload);

    record TxEvent(long position, String streamId, String eventType, String occurredAt,
                   String actor, Map<String, Object> payload) {
    }

    record Appended(String eventId, long position) {
    }
}
