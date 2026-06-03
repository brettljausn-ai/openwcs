package org.openwcs.integration.host.api;

import jakarta.validation.constraints.NotNull;

/** Canonical SKU pushed by a host; upserted into master-data by code. */
public record HostSku(
        @NotNull String code,
        String description,
        String ownerClient,
        Boolean batchTracked,
        Boolean serialTracked,
        Boolean dateTracked) {
}
