package org.openwcs.processdesigner.script;

/**
 * A sandboxed designer-authored script (spec §7.2) failed: it did not parse, breached the statement
 * limit, exceeded the wall-clock timeout, attempted a sandbox escape (host/Java access), or produced
 * an invalid/oversized result. The sandbox catches the violation and the service stays up; this maps
 * to a clean task failure rather than a crash. Carries a safe, designer-facing message only (never a
 * raw stack trace or host detail).
 */
public class ScriptExecutionException extends RuntimeException {

    public ScriptExecutionException(String message) {
        super(message);
    }

    public ScriptExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
