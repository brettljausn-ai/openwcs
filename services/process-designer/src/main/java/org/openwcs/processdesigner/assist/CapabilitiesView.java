package org.openwcs.processdesigner.assist;

/**
 * Phase 3 capabilities the frontend uses to show/hide script editing + AI assist (spec §7.2/§7.3,
 * Feature C). {@code scriptingEnabled} = the off-by-default scripting flag; {@code aiAssistEnabled} =
 * an Anthropic key is configured; {@code canAuthorScript} = the CALLER holds PROCESS_SCRIPT_AUTHOR
 * (always true when security is disabled).
 */
public record CapabilitiesView(boolean scriptingEnabled, boolean aiAssistEnabled, boolean canAuthorScript) {
}
