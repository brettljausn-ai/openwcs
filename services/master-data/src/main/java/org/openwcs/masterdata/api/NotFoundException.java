package org.openwcs.masterdata.api;

/** Thrown when a referenced master-data resource does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " not found: " + id);
    }
}
