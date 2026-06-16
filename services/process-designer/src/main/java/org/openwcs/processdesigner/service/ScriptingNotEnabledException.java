package org.openwcs.processdesigner.service;

/**
 * The caller tried to save or publish a definition that contains a sandboxed script step (spec §7.2)
 * while scripting is disabled by config ({@code openwcs.process-designer.scripting.enabled=false},
 * the default). Maps to HTTP 422.
 */
public class ScriptingNotEnabledException extends RuntimeException {
    public ScriptingNotEnabledException(String message) {
        super(message);
    }
}
