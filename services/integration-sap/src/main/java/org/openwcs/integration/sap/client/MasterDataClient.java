package org.openwcs.integration.sap.client;

/** Port for syncing host reference data into the master-data catalogs (build.md §16). */
public interface MasterDataClient {

    /** Create or update a route in the master-data Route catalog (keyed by code). */
    UpsertResult upsertRoute(RouteDto route);

    enum UpsertResult { CREATED, UPDATED }

    /** A route as transacted from the host (SAP); {@code hostRef} is its id there. */
    record RouteDto(String code, String name, String region, String hostRef) {
    }
}
