package org.openwcs.process.delegate;

import org.flowable.engine.delegate.DelegateExecution;

/** Shared readers for optional outbound process variables. */
final class ProcessVariables {

    private ProcessVariables() {
    }

    /** The optional {@code allowShort} variable (Boolean or String); defaults to false. */
    static boolean allowShort(DelegateExecution execution) {
        Object raw = execution.getVariable("allowShort");
        if (raw instanceof Boolean bool) {
            return bool;
        }
        return raw != null && Boolean.parseBoolean(raw.toString());
    }
}
