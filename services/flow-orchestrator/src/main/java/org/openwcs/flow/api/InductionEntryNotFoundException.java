package org.openwcs.flow.api;

import java.util.UUID;

/** Raised when an induction queue entry id cannot be resolved. */
public class InductionEntryNotFoundException extends RuntimeException {
    public InductionEntryNotFoundException(UUID entryId) {
        super("Induction entry not found: " + entryId);
    }
}
