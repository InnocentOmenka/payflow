package com.innocodes.payflow.merchant.membership;

import com.innocodes.payflow.merchant.dto.response.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = MembershipController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MembershipExceptionHandler {
    @ExceptionHandler(MembershipNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Resource not found", request);
    }
    @ExceptionHandler({MembershipConflictException.class, DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<ApiErrorResponse> conflict(HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Membership conflicts with current state", request);
    }
    @ExceptionHandler(MembershipRequestException.class)
    ResponseEntity<ApiErrorResponse> invalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid membership request", request);
    }
    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI(), List.of()));
    }
}
