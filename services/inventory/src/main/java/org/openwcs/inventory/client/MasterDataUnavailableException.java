package org.openwcs.inventory.client;

/**
 * Master-data could not be reached (or answered without a usable location). Location bookings that
 * depend on it are rejected with 503 rather than guessed; callers treat the booking as best-effort.
 */
public class MasterDataUnavailableException extends RuntimeException {

    public MasterDataUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
