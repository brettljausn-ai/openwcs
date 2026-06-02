package org.openwcs.iam.api;

/** Thrown when a referenced IAM resource (user / role) does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " not found: " + id);
    }
}
