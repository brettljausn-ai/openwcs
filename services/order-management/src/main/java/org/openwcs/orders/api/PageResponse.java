package org.openwcs.orders.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable page envelope (matches the master-data/inventory page shape). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
