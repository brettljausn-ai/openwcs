package org.openwcs.processdesigner.api;

import java.time.Instant;
import java.util.UUID;
import org.openwcs.processdesigner.domain.ProcessDefinition;

/** A lightweight definition summary (no JSON body) for list views. */
public record DefinitionSummary(
        UUID id,
        String processKey,
        int version,
        String status,
        String title,
        String icon,
        Instant publishedAt,
        String publishedBy,
        Instant updatedAt) {

    public static DefinitionSummary from(ProcessDefinition d) {
        return new DefinitionSummary(d.getId(), d.getProcessKey(), d.getVersion(), d.getStatus(),
                d.getTitle(), d.getIcon(), d.getPublishedAt(), d.getPublishedBy(), d.getUpdatedAt());
    }
}
