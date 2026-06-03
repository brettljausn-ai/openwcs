package org.openwcs.counting.api;

/** Thrown when a counting aggregate (task / line / schedule) is not found. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
