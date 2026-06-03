package org.openwcs.gtp.api;

/** Thrown when a referenced gtp resource does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " not found: " + id);
    }
}
