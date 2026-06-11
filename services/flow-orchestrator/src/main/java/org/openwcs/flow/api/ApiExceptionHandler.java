package org.openwcs.flow.api;

import org.openwcs.flow.client.NoAdapterException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/** Maps flow-orchestrator failures to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DeviceTaskNotFoundException.class)
    public ProblemDetail onNotFound(DeviceTaskNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Device task not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(InductionEntryNotFoundException.class)
    public ProblemDetail onInductionEntryNotFound(InductionEntryNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Induction entry not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** No adapter is configured for the task's equipment family. */
    @ExceptionHandler(NoAdapterException.class)
    public ProblemDetail onNoAdapter(NoAdapterException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("No adapter configured");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** The adapter was unreachable / errored while dispatching. */
    @ExceptionHandler(RestClientException.class)
    public ProblemDetail onAdapterFailure(RestClientException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_GATEWAY);
        problem.setTitle("Adapter unavailable");
        problem.setDetail("The equipment adapter could not be reached; the task was recorded as FAILED.");
        return problem;
    }

    /** A malformed request (e.g. a topology edge referencing an unknown node code). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadRequest(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad request");
        problem.setDetail(ex.getMessage());
        return problem;
    }
}
