package org.openwcs.inventory.service;

import java.util.UUID;

/** Thrown when a handling-unit id cannot be found. */
public class HandlingUnitNotFoundException extends RuntimeException {

    public HandlingUnitNotFoundException(UUID id) {
        super("Handling unit not found: " + id);
    }
}
