package org.openwcs.processdesigner.assist;

import java.util.List;
import java.util.Map;
import org.openwcs.processdesigner.verify.VerifyFields;

/**
 * Phase 3 capabilities the frontend uses to show/hide script editing + AI assist (spec §7.2/§7.3,
 * Feature C). {@code scriptingEnabled} = the off-by-default scripting flag; {@code aiAssistEnabled} =
 * an Anthropic key is configured; {@code canAuthorScript} = the CALLER holds PROCESS_SCRIPT_AUTHOR
 * (always true when security is disabled); {@code verifyKinds} = the server-driven list of scan-verify
 * kinds the screen "Verify" picker offers ({@code barcode}, {@code sku}, {@code location},
 * {@code skuScan}); {@code verifyFields} = the per-kind catalog of resolvable fields (each a
 * {@code {key, label}}) the designer offers for that kind's {@code verify.write} and the runtime reads
 * from {@code VerifyResult.fields} (a location resolves different attributes than a SKU).
 */
public record CapabilitiesView(boolean scriptingEnabled, boolean aiAssistEnabled, boolean canAuthorScript,
                               List<String> verifyKinds,
                               Map<String, List<VerifyFields.Field>> verifyFields) {
}
