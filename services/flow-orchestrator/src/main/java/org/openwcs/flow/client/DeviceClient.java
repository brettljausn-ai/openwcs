package org.openwcs.flow.client;

import java.util.Map;
import org.openwcs.flow.domain.DeviceTask;

/**
 * The uniform device contract (build.md §8) as seen by the orchestrator: dispatch a task to
 * the right equipment adapter and get its result. The first implementation calls the adapter
 * synchronously over HTTP (suitable for simulators); the production contract is asynchronous
 * over Kafka (`device.tasks` / `device.results`, build.md §9).
 */
public interface DeviceClient {

    DeviceResult execute(DeviceTask task);

    /** Adapter response: {@code status} is COMPLETED or FAILED. */
    record DeviceResult(String status, String detail, Map<String, Object> resultPayload) {
    }
}
