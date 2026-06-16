package org.openwcs.processdesigner.assist;

/** The AI task-assist model call failed (e.g. an Anthropic API error). Maps to HTTP 502. */
public class TaskAssistException extends RuntimeException {
    public TaskAssistException(String message) {
        super(message);
    }
}
