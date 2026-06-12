package org.openwcs.inventory.api;

import org.openwcs.inventory.client.MasterDataUnavailableException;
import org.openwcs.inventory.service.HandlingUnitNotFoundException;
import org.openwcs.inventory.service.InsufficientStockException;
import org.openwcs.inventory.service.ReservationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps inventory domain failures to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail onInsufficientStock(InsufficientStockException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Insufficient stock");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ProblemDetail onNotFound(ReservationNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Reservation not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    @ExceptionHandler(HandlingUnitNotFoundException.class)
    public ProblemDetail onNotFound(HandlingUnitNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Handling unit not found");
        problem.setDetail(ex.getMessage());
        return problem;
    }

    /** Master-data could not be reached; location-dependent bookings are rejected, never guessed. */
    @ExceptionHandler(MasterDataUnavailableException.class)
    public ProblemDetail onMasterDataUnavailable(MasterDataUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Master data unavailable");
        problem.setDetail(ex.getMessage());
        return problem;
    }
}
