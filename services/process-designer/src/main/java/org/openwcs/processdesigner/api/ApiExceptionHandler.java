package org.openwcs.processdesigner.api;

import java.util.Map;
import org.openwcs.processdesigner.service.DefinitionInvalidException;
import org.openwcs.processdesigner.task.TaskExecutionException;
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
}
