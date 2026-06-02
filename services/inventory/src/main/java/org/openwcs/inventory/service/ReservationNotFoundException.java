package org.openwcs.inventory.service;

import java.util.UUID;

/** Thrown when a reservation id cannot be found. */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID id) {
        super("Reservation not found: " + id);
    }
}
