package com.ecommerce.payment.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(PaymentNotFoundException ex, HttpServletRequest req) {
        return resp(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(PaymentAlreadyProcessedException.class)
    public ResponseEntity<ErrorResponse> alreadyProcessed(PaymentAlreadyProcessedException ex, HttpServletRequest req) {
        return resp(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ErrorResponse> invalidState(InvalidPaymentStateException ex, HttpServletRequest req) {
        return resp(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage())).collect(Collectors.toList());
        return ResponseEntity.badRequest().body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(400).error("Bad Request").message("Validation failed").path(req.getRequestURI())
            .correlationId(MDC.get("correlationId")).fieldErrors(errors).build());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> general(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> resp(HttpStatus status, String error, String msg, String path) {
        return ResponseEntity.status(status).body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(status.value()).error(error).message(msg).path(path).correlationId(MDC.get("correlationId")).build());
    }
}
