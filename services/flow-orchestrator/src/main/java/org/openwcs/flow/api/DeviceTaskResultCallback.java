package org.openwcs.flow.api;

import java.util.Map;

/**
 * Terminal result an asynchronous adapter/emulator posts back for a DISPATCHED device task
 * ({@code POST /api/flow/device-tasks/{id}/result}). {@code status} is COMPLETED or FAILED.
 */
public record DeviceTaskResultCallback(String status, String detail, Map<String, Object> resultPayload) {
}
