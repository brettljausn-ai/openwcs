package org.openwcs.processdesigner.service;

/**
 * The caller tried to save or publish a definition that contains a sandboxed script step (spec §7.2)
 * without holding {@code PROCESS_SCRIPT_AUTHOR}. Maps to HTTP 403 (defense in depth alongside the
 * off-by-default scripting flag).
 */
public class ScriptingForbiddenException extends RuntimeException {
    public ScriptingForbiddenException(String message) {
        super(message);
    }
}
