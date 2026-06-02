package org.openwcs.masterdata.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One element of a dispatch-label design, positioned on the label in millimetres from the
 * top-left. Persisted as part of {@link LabelTemplate#getElements()} (JSONB).
 *
 * <p>{@code type} is TEXT | ADDRESS | BARCODE | IMAGE. The printed value is the static
 * {@code value} if set, otherwise resolved at render time from the supplied data by
 * {@code key} (e.g. {@code shipToName}, {@code addressBlock}, {@code trackingBarcode}).
 * {@code barcodeSymbology} (e.g. CODE128, QR) applies to BARCODE; {@code fontPt} to text.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelElement(
        String type,
        String key,
        String value,
        double xMm,
        double yMm,
        double widthMm,
        double heightMm,
        Double fontPt,
        String barcodeSymbology) {
}
