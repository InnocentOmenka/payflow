package com.innocodes.payflow.merchant.exception;

import com.innocodes.payflow.merchant.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public void unauthorized(org.springframework.security.core.AuthenticationException exception,
            HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException, jakarta.servlet.ServletException {
        com.innocodes.payflow.security.SecurityErrors.unauthorized().commence(request, response, exception);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void forbidden(org.springframework.security.access.AccessDeniedException exception,
            HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException, jakarta.servlet.ServletException {
        com.innocodes.payflow.security.SecurityErrors.forbidden().handle(request, response, exception);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).sorted().toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> constraints(ConstraintViolationException exception, HttpServletRequest request) {
        var details = exception.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request, details);
    }

    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid request body or parameter", request, List.of());
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> notFound(MerchantNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MerchantConflictException.class)
    public ResponseEntity<ApiErrorResponse> conflict(MerchantConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> optimisticConflict(OptimisticLockingFailureException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Merchant was concurrently updated; retrieve the latest version before retrying",
                request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> integrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return error(HttpStatus.CONFLICT, "Merchant identifier or business email already exists", request, List.of());
            }
        }
        // Do not expose SQL, values, or database constraint details.
        return error(HttpStatus.BAD_REQUEST, "Merchant data violates database constraints", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedMethod(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        var response = error(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not supported", request, List.of());
        return ResponseEntity.status(response.getStatusCode()).allow(exception.getSupportedHttpMethods()
                .toArray(org.springframework.http.HttpMethod[]::new)).body(response.getBody());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> unsupportedMedia(HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type is not supported", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> missingRoute(NoResourceFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Resource not found", request, List.of());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> responseStatus(org.springframework.web.server.ResponseStatusException exception, HttpServletRequest request) {
        return error(HttpStatus.valueOf(exception.getStatusCode().value()), exception.getReason(), request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> invalidArgument(IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid webhook destination", request, List.of());
    }

    @ExceptionHandler(com.innocodes.payflow.webhook.SecretUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> secretUnavailable(Exception exception,HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"Signing secret provider is unavailable",request,List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class)
                .error("Merchant request failed; exception type={}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message, HttpServletRequest request, List<String> details) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(Instant.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI(), details));
    }
}
