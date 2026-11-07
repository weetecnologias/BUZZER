package com.buzzer.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorBody(String code, String message, Map<String, Object> details, String traceId) {}

    public record ErrorEnvelope(ErrorBody error) {}

    private ResponseEntity<ErrorEnvelope> build(ErrorCode code, String message, Map<String, Object> details) {
        String traceId = UUID.randomUUID().toString();
        return ResponseEntity.status(code.status())
                .body(new ErrorEnvelope(new ErrorBody(code.name(), message, details, traceId)));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorEnvelope> business(BusinessException ex) {
        return build(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> validation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, "Request validation failed", Map.of("fields", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorEnvelope> badRequest(Exception ex) {
        return build(ErrorCode.VALIDATION_FAILED, "Malformed request", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorEnvelope> tooLarge(MaxUploadSizeExceededException ex) {
        return build(ErrorCode.VALIDATION_FAILED, "Uploaded file is too large", Map.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorEnvelope> denied(Exception ex) {
        return build(ErrorCode.FORBIDDEN, "You do not have permission to perform this action", Map.of());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorEnvelope> duplicate(DuplicateKeyException ex) {
        log.info("Duplicate key: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.DUPLICATE, "A record with the same unique value already exists", Map.of());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorEnvelope> method(Exception ex) {
        return build(ErrorCode.METHOD_NOT_ALLOWED, "HTTP method not allowed for this endpoint", Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorEnvelope> noResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "Endpoint not found", Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return build(ErrorCode.INTERNAL_ERROR, "Something went wrong. Please try again.", Map.of());
    }
}
