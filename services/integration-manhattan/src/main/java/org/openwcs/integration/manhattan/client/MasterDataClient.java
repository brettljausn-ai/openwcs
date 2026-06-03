package org.openwcs.integration.manhattan.client;

import java.util.UUID;

/** Port to master-data for resolving Manhattan item ids to SKU ids. */
public interface MasterDataClient {

    /** Resolve a SKU's id from its (vendor) code, or null if unknown. */
    UUID skuIdByCode(String code);
}
