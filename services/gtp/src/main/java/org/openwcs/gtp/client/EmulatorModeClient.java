package org.openwcs.gtp.client;

/**
 * Reads the global hardware-emulator flag (owned by master-data) so pick-to-light driving can pick
 * its target: when emulator mode is ON, light tasks go to the equipment emulator; when OFF, to the
 * real PTL adapter. Implementations default to OFF on failure. Mirrors flow-orchestrator's reader.
 */
public interface EmulatorModeClient {

    /** Whether hardware-emulator mode is currently ON. Implementations default to OFF on failure. */
    boolean enabled();
}
