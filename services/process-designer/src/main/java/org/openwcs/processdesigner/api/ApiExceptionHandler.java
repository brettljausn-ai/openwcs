package org.openwcs.processdesigner.api;

import java.util.Map;
import org.openwcs.processdesigner.assist.AssistNotConfiguredException;
import org.openwcs.processdesigner.assist.TaskAssistException;
import org.openwcs.processdesigner.script.ScriptExecutionException;
import org.openwcs.processdesigner.service.DefinitionInvalidException;
import org.openwcs.processdesigner.service.ScriptingForbiddenException;
import org.openwcs.processdesigner.service.ScriptingNotEnabledException;
import org.openwcs.processdesigner.task.TaskExecutionException;
import org.openwcs.processdesigner.verify.VerifyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps process-designer exceptions to HTTP responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** Publish-time validation failure: 422 with the list of problems. */
    @ExceptionHandler(DefinitionInvalidException.class)
    public ResponseEntity<Map<String, Object>> invalid(DefinitionInvalidException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", e.getMessage(), "problems", e.problems()));
    }

    /** A curated task step failed: 502 so the client queues/retries; the instance did not advance. */
    @ExceptionHandler(TaskExecutionException.class)
    public ResponseEntity<Map<String, Object>> taskFailed(TaskExecutionException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    /**
     * The scan-verify proxy's downstream call to master-data failed (unreachable / 4xx / 5xx): 502
     * with a clean message, so the proxy never 500s the service. A clean {@code found:false} from
     * master-data is NOT routed here.
     */
    @ExceptionHandler(VerifyException.class)
    public ResponseEntity<Map<String, Object>> verifyFailed(VerifyException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    /**
     * A sandboxed script (spec §7.2) failed cleanly — syntax error, statement-limit breach, timeout,
     * sandbox-escape attempt, or invalid/oversized output. 502 (the task did not advance the
     * instance); the service stays up.
     */
    @ExceptionHandler(ScriptExecutionException.class)
    public ResponseEntity<Map<String, Object>> scriptFailed(ScriptExecutionException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    /** Saving/publishing a script-bearing definition without PROCESS_SCRIPT_AUTHOR: 403. */
    @ExceptionHandler(ScriptingForbiddenException.class)
    public ResponseEntity<Map<String, Object>> scriptingForbidden(ScriptingForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    /** Saving/publishing a script-bearing definition while scripting is disabled by config: 422. */
    @ExceptionHandler(ScriptingNotEnabledException.class)
    public ResponseEntity<Map<String, Object>> scriptingDisabled(ScriptingNotEnabledException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
    }

    /** AI task-assist invoked with no Anthropic key configured: 503 (the context still starts). */
    @ExceptionHandler(AssistNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> assistNotConfigured(AssistNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    /** The AI task-assist model call failed (e.g. Anthropic API error): 502. */
    @ExceptionHandler(TaskAssistException.class)
    public ResponseEntity<Map<String, Object>> assistFailed(TaskAssistException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
