package com.arnav.orders.web;

import com.arnav.orders.service.Errors;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** One place that decides which domain error becomes which HTTP status. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static ResponseEntity<Dtos.ErrorResponse> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new Dtos.ErrorResponse(error, message));
    }

    @ExceptionHandler(Errors.NotFound.class)
    ResponseEntity<Dtos.ErrorResponse> notFound(Errors.NotFound e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(Errors.InsufficientStock.class)
    ResponseEntity<Dtos.ErrorResponse> outOfStock(Errors.InsufficientStock e) {
        return body(HttpStatus.CONFLICT, "insufficient_stock", e.getMessage());
    }

    @ExceptionHandler(Errors.InvalidTransition.class)
    ResponseEntity<Dtos.ErrorResponse> badTransition(Errors.InvalidTransition e) {
        return body(HttpStatus.CONFLICT, "invalid_transition", e.getMessage());
    }

    @ExceptionHandler(Errors.IdempotencyConflict.class)
    ResponseEntity<Dtos.ErrorResponse> keyReused(Errors.IdempotencyConflict e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Dtos.ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage()).collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "validation_failed", detail);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Dtos.ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return body(HttpStatus.BAD_REQUEST, "missing_header", e.getHeaderName() + " header is required");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Dtos.ErrorResponse> integrity(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "conflict", "The request conflicts with existing data");
    }
}
