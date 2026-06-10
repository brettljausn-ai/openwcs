package org.openwcs.flow.client;

/**
 * Reads the global hardware-emulator flag (owned by master-data) so device-task dispatch can pick
 * its target: when emulator mode is ON, tasks go to the single equipment-emulator; when OFF, to the
 * real per-family adapters.
 */
public interface EmulatorModeClient {

    /** Whether hardware-emulator mode is currently ON. Implementations default to OFF on failure. */
    boolean enabled();
}
