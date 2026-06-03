package org.openwcs.slotting.api;

/** Thrown when a referenced slotting resource does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " not found: " + id);
    }
}
