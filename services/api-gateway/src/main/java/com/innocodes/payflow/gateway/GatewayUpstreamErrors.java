package com.innocodes.payflow.gateway;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class GatewayUpstreamErrors {
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> unavailable(ResourceAccessException failure) {
        boolean timeout = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) timeout = true;
        }
        var status = timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of(
                "status", status.value(), "error", status.getReasonPhrase(),
                "message", timeout ? "Payment service timed out" : "Payment service unavailable"));
    }
}
