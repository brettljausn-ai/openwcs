package org.openwcs.flow.client;

/** Thrown when no adapter base URL is configured for an equipment family. */
public class NoAdapterException extends RuntimeException {

    public NoAdapterException(String family) {
        super("No device adapter configured for family: " + family);
    }
}
