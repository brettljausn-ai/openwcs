package org.openwcs.integration.host.api;

import jakarta.validation.Valid;
import org.openwcs.integration.host.client.MasterDataClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Host-driven master-data intake on the canonical Host API. */
@RestController
@RequestMapping("/api/host/masterdata")
public class HostReferenceController {

    private final MasterDataClient masterData;

    public HostReferenceController(MasterDataClient masterData) {
        this.masterData = masterData;
    }

    @PostMapping("/skus")
    public UpsertResponse upsertSku(@Valid @RequestBody HostSku sku) {
        MasterDataClient.UpsertResult result = masterData.upsertSku(new MasterDataClient.SkuDto(
                sku.code(), sku.description(), sku.ownerClient(),
                sku.batchTracked(), sku.serialTracked(), sku.dateTracked()));
        return new UpsertResponse(sku.code(), result.name());
    }

    public record UpsertResponse(String code, String result) {
    }
}
