package org.openwcs.processdesigner.task;

/**
 * A curated task step failed (the target service errored, was unreachable, or rejected the call).
 * Maps to HTTP 502 so the client can queue/retry; the instance's {@code current_step} is NOT
 * advanced, so a retry re-posts the same checkpoint.
 */
public class TaskExecutionException extends RuntimeException {
    public TaskExecutionException(String message) {
        super(message);
    }

    public TaskExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
