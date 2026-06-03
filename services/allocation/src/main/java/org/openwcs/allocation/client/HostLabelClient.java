package org.openwcs.allocation.client;

import java.util.UUID;

/**
 * Port for obtaining a dispatch-label barcode from the host system (carrier/WMS) for a single
 * shipper. A shipper's barcode is only knowable after cubing determines the cartons, so it is
 * requested per shipper at that point (build.md §7; ADR 0002). The production implementation
 * calls the host via the integration gateway; the default here is a simulator.
 */
public interface HostLabelClient {

    /**
     * Request a unique label barcode for one shipper of an order.
     *
     * @param seqNo carton sequence within the order (1..N)
     */
    String requestBarcode(String orderRef, UUID warehouseId, String serviceCode, String routeCode, int seqNo);
}
