package org.openwcs.processdesigner.api;

/** A definition or instance was not found. Maps to HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
