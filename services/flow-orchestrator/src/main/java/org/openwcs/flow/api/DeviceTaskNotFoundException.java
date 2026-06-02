package org.openwcs.flow.api;

import java.util.UUID;

/** Raised when a device task id cannot be resolved. */
public class DeviceTaskNotFoundException extends RuntimeException {
    public DeviceTaskNotFoundException(UUID taskId) {
        super("Device task not found: " + taskId);
    }
}
