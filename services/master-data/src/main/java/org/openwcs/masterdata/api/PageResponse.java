package org.openwcs.masterdata.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable page envelope matching the OpenAPI {@code Page} schema (avoids Spring's PageImpl JSON). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
