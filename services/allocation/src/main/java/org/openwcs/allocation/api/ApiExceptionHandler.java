package org.openwcs.allocation.api;

import org.openwcs.allocation.service.CubingEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** Maps allocation failures to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AllocationNotFoundException.class)
    public ProblemDetail onNotFound(AllocationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Allocation not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(CubingEngine.CannotCubeException.class)
    public ProblemDetail onCannotCube(CubingEngine.CannotCubeException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Cannot cube order");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** e.g. batch picking requested but disabled for the warehouse. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onIllegalState(IllegalStateException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Operation not allowed");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** master-data or inventory was unreachable / errored while allocating. */
    @ExceptionHandler(RestClientException.class)
    public ProblemDetail onDownstreamFailure(RestClientException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Upstream service unavailable");
        problem.setDetail("Allocation failed talking to master-data or inventory; no stock was held.");
        return problem;
    }
}
