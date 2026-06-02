package org.openwcs.masterdata.api;

import java.util.List;

/** Result of a bulk SKU import (build.md §4.1). */
public record BulkImportReport(int total, int created, int updated, int failed, List<ImportError> errors) {

    public record ImportError(int row, String code, String message) {
    }
}
