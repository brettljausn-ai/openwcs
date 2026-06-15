package org.openwcs.assistant.api;

import java.util.Map;
import org.openwcs.assistant.chat.ChatModelException;
import org.openwcs.assistant.service.NotConfiguredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps assistant API exceptions to HTTP responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Assistant disabled / no key → 409 with a clear message. */
    @ExceptionHandler(NotConfiguredException.class)
    public ResponseEntity<Map<String, String>> notConfigured(NotConfiguredException e) {
        log.info("chat rejected (409): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** Model API error → 502. */
    @ExceptionHandler(ChatModelException.class)
    public ResponseEntity<Map<String, String>> modelError(ChatModelException e) {
        log.warn("chat failed (502): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    /** Bad request body → 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid request body"));
    }
}
