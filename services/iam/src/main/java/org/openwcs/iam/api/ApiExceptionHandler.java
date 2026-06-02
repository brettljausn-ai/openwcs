package org.openwcs.iam.api;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps IAM failures to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** e.g. an unknown permission code (not in the catalog). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onConflict(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail("The request conflicts with existing data (e.g. a duplicate username or role name).");
        return problem;
    }
}
