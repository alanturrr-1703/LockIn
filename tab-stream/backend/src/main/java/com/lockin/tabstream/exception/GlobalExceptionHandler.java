package com.lockin.tabstream.exception;

import com.lockin.tabstream.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Centralised exception handler for all REST controllers.
 *
 * <p>Each handler method maps a specific exception type to an appropriate
 * HTTP status code and wraps the error message in an {@link ApiResponse}
 * envelope so that clients always receive a consistent response shape.
 *
 * <p>The catch-all {@link Exception} handler is intentionally last in the
 * file; Spring will prefer the more-specific handlers above it.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(
        GlobalExceptionHandler.class
    );

    // -------------------------------------------------------------------------
    // 400 – Validation errors
    // -------------------------------------------------------------------------

    /**
     * Handles Bean Validation failures triggered by {@code @Valid} on controller
     * method parameters (e.g. {@code @RequestBody} with constraint annotations).
     *
     * <p>All field errors are collected into a single comma-separated message so
     * that clients receive the full list of problems in one round-trip.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
        MethodArgumentNotValidException ex
    ) {
        String details = ex
            .getBindingResult()
            .getFieldErrors()
            .stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));

        String message = "Validation failed: " + details;
        log.debug("MethodArgumentNotValidException: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiResponse.error(message)
        );
    }

    /**
     * Handles constraint violations raised outside of a controller parameter
     * context — e.g. on service-layer method arguments annotated with
     * {@code @Validated}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
        ConstraintViolationException ex
    ) {
        String details = ex
            .getConstraintViolations()
            .stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .collect(Collectors.joining("; "));

        String message = "Constraint violation: " + details;
        log.debug("ConstraintViolationException: {}", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiResponse.error(message)
        );
    }

    /**
     * Handles malformed JSON bodies and cases where the request body cannot be
     * deserialised into the expected type (e.g. a string value where a number
     * is expected, or a payload that exceeds Jackson's configured limits).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
        HttpMessageNotReadableException ex
    ) {
        // Do not leak internal Jackson details to the client; log them at DEBUG instead
        log.debug("HttpMessageNotReadableException: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ApiResponse.error("Malformed JSON or payload too large")
        );
    }

    // -------------------------------------------------------------------------
    // 413 – Payload too large
    // -------------------------------------------------------------------------

    /**
     * Handles multipart upload or request-body size limit violations configured
     * via {@code spring.servlet.multipart.max-request-size} /
     * {@code spring.servlet.multipart.max-file-size}.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(
        MaxUploadSizeExceededException ex
    ) {
        log.warn("MaxUploadSizeExceededException: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(
            ApiResponse.error(
                "Request payload exceeds the maximum allowed size of 15 MB"
            )
        );
    }

    // -------------------------------------------------------------------------
    // 500 – Catch-all
    // -------------------------------------------------------------------------

    /**
     * Catch-all handler for any exception not matched by a more specific handler
     * above.
     *
     * <p>The full stack trace is logged at ERROR level for operator visibility,
     * but only a generic message is returned to the client to avoid leaking
     * internal implementation details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
        Exception ex
    ) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiResponse.error(
                "An unexpected error occurred. Please try again later."
            )
        );
    }
}
