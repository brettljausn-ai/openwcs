package org.openwcs.masterdata.api;

import java.util.Map;

/**
 * Render a label template to a print payload. {@code format} is ZPL (default) or PDF;
 * {@code data} supplies the values for the template's data-bound elements (by element key).
 */
public record RenderLabelRequest(String format, Map<String, String> data) {
}
