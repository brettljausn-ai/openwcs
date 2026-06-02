package org.openwcs.txlog.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps persistence/validation failures to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** A duplicate (streamId, seq) means a concurrency conflict on append (build.md §5.2). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onConflict(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Append conflict");
        problem.setDetail("An event with this (streamId, seq) already exists; reload and retry with the next sequence.");
        return problem;
    }
}
