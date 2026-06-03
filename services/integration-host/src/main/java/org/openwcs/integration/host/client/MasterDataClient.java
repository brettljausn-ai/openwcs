package org.openwcs.integration.host.client;

/** Port to master-data for host-driven reference-data sync (SKU upsert by code). */
public interface MasterDataClient {

    UpsertResult upsertSku(SkuDto sku);

    enum UpsertResult { CREATED, UPDATED }

    /** Canonical SKU as transacted from the host. */
    record SkuDto(String code, String description, String ownerClient,
                  Boolean batchTracked, Boolean serialTracked, Boolean dateTracked) {
    }
}
