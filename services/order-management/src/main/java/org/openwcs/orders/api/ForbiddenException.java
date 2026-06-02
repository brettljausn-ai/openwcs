package org.openwcs.orders.api;

import org.openwcs.common.security.Permission;

/** Thrown when the caller's roles do not grant the permission an endpoint requires. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(Permission required) {
        super("Missing required permission: " + required);
    }
}
