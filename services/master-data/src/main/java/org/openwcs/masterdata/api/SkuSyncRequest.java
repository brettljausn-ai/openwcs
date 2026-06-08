package org.openwcs.masterdata.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

/**
 * One SKU as pushed by the host on the master-data sync path, carrying its full
 * unit-of-measure hierarchy and barcodes inline (build.md §6, §16).
 *
 * <p>References inside the payload are by <em>code</em>, never by internal id: a UoM names its
 * parent by {@code parentCode} and a barcode names its packaging level by {@code uomCode}, so the
 * host never has to know openWCS-generated UUIDs. The host is authoritative — the nested lists
 * fully replace what is stored for the SKU (UoMs/barcodes the host omits are removed).
 */
public record SkuSyncRequest(
        @NotBlank String code,
        String description,
        String ownerClient,
        Boolean batchTracked,
        Boolean serialTracked,
        Boolean dateTracked,
        @Valid List<UomSync> uoms,
        @Valid List<BarcodeSync> barcodes) {

    /** A unit of measure in the SKU's packaging hierarchy; parent named by code. */
    public record UomSync(
            @NotBlank String code,
            String parentCode,
            BigDecimal qtyInParent,
            BigDecimal lengthMm,
            BigDecimal widthMm,
            BigDecimal heightMm,
            BigDecimal weightG,
            Boolean baseUnit) {
    }

    /** A barcode bound to a packaging level (UoM) named by code, of a barcode type named by name. */
    public record BarcodeSync(
            @NotBlank String value,
            String uomCode,
            String type) {
    }
}
