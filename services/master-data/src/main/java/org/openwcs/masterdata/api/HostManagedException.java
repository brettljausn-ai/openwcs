package org.openwcs.masterdata.api;

/**
 * Thrown when an <em>interactive</em> caller attempts to create, edit, or delete master data
 * that the WCS does not own — SKU, unit-of-measure, and barcode are owned by the host/ERP and
 * arrive only via host sync (build.md §6, §16). Interactive (gateway-routed) writes are rejected
 * with HTTP 403; the host-sync ingestion path (a direct internal service-to-service call that
 * never carries the gateway-injected {@code X-Auth-User} identity) is allowed to upsert them.
 */
public class HostManagedException extends RuntimeException {

    public HostManagedException(String entity) {
        super(entity + " is managed by the host system and is read-only in the WCS. "
                + "It can only be changed via host data sync.");
    }
}
