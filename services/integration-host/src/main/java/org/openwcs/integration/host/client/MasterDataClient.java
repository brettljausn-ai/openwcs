package org.openwcs.integration.host.client;

import java.math.BigDecimal;
import java.util.List;

/** Port to master-data for host-driven reference-data sync (SKUs with UoMs and barcodes, by code). */
public interface MasterDataClient {

    /** Sync a batch of SKUs (with their UoMs and barcodes) into master-data; returns per-SKU results. */
    SyncReport syncSkus(List<SkuDto> skus);

    enum Action { CREATED, UPDATED }

    /** Canonical SKU as transacted from the host, with its UoM hierarchy and barcodes inline. */
    record SkuDto(String code, String description, String ownerClient,
                  Boolean batchTracked, Boolean serialTracked, Boolean dateTracked,
                  List<UomDto> uoms, List<BarcodeDto> barcodes) {
    }

    /** A unit of measure in the SKU's packaging hierarchy; parent named by code. */
    record UomDto(String code, String parentCode, BigDecimal qtyInParent,
                  BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm, BigDecimal weightG,
                  Boolean baseUnit) {
    }

    /** A barcode bound to a packaging level (UoM) named by code, of a barcode type named by name. */
    record BarcodeDto(String value, String uomCode, String type) {
    }

    /** Per-SKU outcome of a sync batch. */
    record SkuResult(String code, Action action, int uoms, int barcodes) {
    }

    /** Summary of a sync batch returned by master-data. */
    record SyncReport(int received, int created, int updated, List<SkuResult> results) {
    }
}
