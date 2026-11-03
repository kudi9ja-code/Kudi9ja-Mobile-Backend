package com.quadrilateral.kudi9ja.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Turns every failure into one {@link ApiError} shape.
 *
 * <p>Nothing internal reaches the client: a stack trace, a SQL constraint name
 * or a class name would tell an attacker more about the schema than a customer
 * ever needs. Those are logged and answered with a flat apology.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        // A refusal is the normal course of business, not a fault: log it at
        // debug so a wallet with a short balance does not fill the error log.
        log.debug("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.code().status())
                .body(ApiError.of(ex.code(), ex.getMessage(), ex.details(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        String first = fields.values().stream().findFirst().map(Object::toString)
                .orElse("Some of what you sent was not accepted.");
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(ErrorCode.VALIDATION_FAILED, first, fields, request.getRequestURI()));
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleMalformed(Exception ex, HttpServletRequest request) {
        log.debug("Malformed request {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .body(ApiError.of(
                        ErrorCode.VALIDATION_FAILED,
                        "That request could not be read. Check the fields and try again.",
                        Map.of(),
                        request.getRequestURI()));
    }

    /**
     * A multipart request that left out a required part.
     *
     * <p>In practice this is a pay-in claim submitted without its receipt, and
     * a receipt is mandatory: a claim cannot be submitted without one. Left
     * unhandled it surfaces as a 500, which tells the customer that something
     * broke when in fact they simply have not attached anything yet — and hands
     * back a shape no client is prepared to read.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> handleMissingPart(
            MissingServletRequestPartException ex, HttpServletRequest request) {

        boolean isReceipt = "receipt".equals(ex.getRequestPartName());
        ErrorCode code = isReceipt ? ErrorCode.RECEIPT_REQUIRED : ErrorCode.VALIDATION_FAILED;

        return ResponseEntity.status(code.status())
                .body(ApiError.of(
                        code,
                        isReceipt
                                ? "Attach the receipt from your bank. We cannot confirm a payment without one."
                                : "That request was missing its \"" + ex.getRequestPartName() + "\" part.",
                        Map.of("missingPart", ex.getRequestPartName()),
                        request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of(
                        ErrorCode.VALIDATION_FAILED,
                        "That upload is too large. Each file must be under 12MB, and all of them together under 52MB.",
                        Map.of(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status())
                .body(ApiError.of(
                        ErrorCode.FORBIDDEN,
                        "You do not have access to that.",
                        Map.of(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.UNAUTHENTICATED.status())
                .body(ApiError.of(
                        ErrorCode.UNAUTHENTICATED,
                        "Sign in to continue.",
                        Map.of(),
                        request.getRequestURI()));
    }

    /**
     * Two requests raced for the same row. The loser is told to retry rather
     * than being handed a half-applied result.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(
                        ErrorCode.CONFLICT,
                        "Something else changed that at the same moment. Try again.",
                        Map.of(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        // The constraint name would leak the schema, so it is logged, not sent.
        log.warn("Integrity violation on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.CONFLICT.status())
                .body(ApiError.of(
                        ErrorCode.CONFLICT,
                        "That conflicts with something already on record.",
                        Map.of(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNoHandler(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(
                        ErrorCode.NOT_FOUND,
                        "There is nothing at that address.",
                        Map.of(),
                        request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(
                        ErrorCode.INTERNAL,
                        "Something went wrong on our side. Nothing was changed.",
                        Map.of(),
                        request.getRequestURI()));
    }
}
