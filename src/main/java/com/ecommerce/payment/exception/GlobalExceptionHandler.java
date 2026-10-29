package com.ecommerce.payment.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every client error is a 4xx with a fixed message per error kind: no Java type names, parser text,
 * SQL, internal URLs or downstream bodies reach the response. Details go to the log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(PaymentNotFoundException ex, HttpServletRequest req) {
        return resp(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> orderNotFound(OrderNotFoundException ex, HttpServletRequest req) {
        return resp(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    // 401/403 bodies carry no path: it can contain the very identifier the caller is not allowed to see.
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException ex, HttpServletRequest req) {
        return resp(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required", null);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(ForbiddenException ex, HttpServletRequest req) {
        return resp(HttpStatus.FORBIDDEN, "Forbidden", "Access denied", null);
    }

    @ExceptionHandler(PaymentAlreadyProcessedException.class)
    public ResponseEntity<ErrorResponse> alreadyProcessed(PaymentAlreadyProcessedException ex, HttpServletRequest req) {
        return resp(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(OrderNotPayableException.class)
    public ResponseEntity<ErrorResponse> orderNotPayable(OrderNotPayableException ex, HttpServletRequest req) {
        return resp(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> concurrentModification(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.CONFLICT, "Conflict", "The payment was modified by another request", req.getRequestURI());
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    public ResponseEntity<ErrorResponse> dependencyUnavailable(DependencyUnavailableException ex, HttpServletRequest req) {
        // The cause was logged where it happened; the caller only learns that the order service could not be asked.
        return resp(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Order service unavailable. Please try again later.", req.getRequestURI());
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ErrorResponse> invalidState(InvalidPaymentStateException ex, HttpServletRequest req) {
        return resp(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidPaymentMethodException.class)
    public ResponseEntity<ErrorResponse> invalidMethod(InvalidPaymentMethodException ex, HttpServletRequest req) {
        return resp(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMediaType(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "Unsupported content type", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> notAcceptable(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable", "Requested media type not acceptable", req.getRequestURI());
    }

    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ErrorResponse> noResource(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.NOT_FOUND, "Not Found", "Resource not found", req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage())).collect(Collectors.toList());
        return ResponseEntity.badRequest().body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(400).error("Bad Request").message("Validation failed").path(req.getRequestURI())
            .correlationId(MDC.get("correlationId")).fieldErrors(errors).build());
    }

    // Spring MVC raises these for malformed client requests. Without explicit handlers they fall
    // through to the Exception catch-all below and are reported as 500. The message is fixed per kind:
    // the framework's text carries DTO class names and Jackson/Java types.
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotSupported(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "Request method not supported", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed request body", req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String name = ex.getName() == null ? "" : ex.getName().replaceAll("[^A-Za-z0-9_.-]", "");
        return resp(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid value for parameter '" + name + "'", req.getRequestURI());
    }

    @ExceptionHandler({
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorResponse> binding(Exception ex, HttpServletRequest req) {
        return resp(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> general(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {} (correlationId={})", req.getMethod(), req.getRequestURI(), MDC.get("correlationId"), ex);
        return resp(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> resp(HttpStatus status, String error, String msg, String path) {
        return ResponseEntity.status(status).body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(status.value()).error(error).message(msg).path(path).correlationId(MDC.get("correlationId")).build());
    }
}
