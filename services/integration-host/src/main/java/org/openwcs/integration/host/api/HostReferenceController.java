package org.openwcs.integration.host.api;

import jakarta.validation.Valid;
import java.util.List;
import org.openwcs.integration.host.client.MasterDataClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Host-driven master-data intake on the canonical Host API. */
@RestController
@RequestMapping("/api/host/masterdata")
public class HostReferenceController {

    private static final Logger log = LoggerFactory.getLogger(HostReferenceController.class);

    private final MasterDataClient masterData;

    public HostReferenceController(MasterDataClient masterData) {
        this.masterData = masterData;
    }

    /**
     * Upsert a batch of SKUs by code, each with its unit-of-measure hierarchy and barcodes inline.
     * The nested lists are authoritative: master-data fully reconciles the SKU's stored UoMs and
     * barcodes against the payload. Returns a per-SKU created/updated report.
     */
    @PostMapping("/skus")
    public MasterDataClient.SyncReport upsertSkus(@RequestBody List<@Valid HostSku> skus) {
        List<MasterDataClient.SkuDto> dtos = skus.stream().map(HostReferenceController::toDto).toList();
        MasterDataClient.SyncReport report = masterData.syncSkus(dtos);
        log.info("host SKU sync accepted: {} SKUs received, {} created, {} updated in master-data",
                report.received(), report.created(), report.updated());
        if (report.results() != null && log.isDebugEnabled()) {
            for (MasterDataClient.SkuResult r : report.results()) {
                log.debug("host SKU sync detail: sku {} {} ({} uoms, {} barcodes)",
                        r.code(), r.action(), r.uoms(), r.barcodes());
            }
        }
        return report;
    }

    private static MasterDataClient.SkuDto toDto(HostSku sku) {
        List<MasterDataClient.UomDto> uoms = sku.uoms() == null ? null : sku.uoms().stream()
                .map(u -> new MasterDataClient.UomDto(u.code(), u.parentCode(), u.qtyInParent(),
                        u.lengthMm(), u.widthMm(), u.heightMm(), u.weightG(), u.baseUnit()))
                .toList();
        List<MasterDataClient.BarcodeDto> barcodes = sku.barcodes() == null ? null : sku.barcodes().stream()
                .map(b -> new MasterDataClient.BarcodeDto(b.value(), b.uomCode(), b.type()))
                .toList();
        return new MasterDataClient.SkuDto(sku.code(), sku.description(), sku.ownerClient(),
                sku.batchTracked(), sku.serialTracked(), sku.dateTracked(), uoms, barcodes);
    }
}
