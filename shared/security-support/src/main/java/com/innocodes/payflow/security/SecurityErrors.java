package com.innocodes.payflow.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/** Filter errors use the existing PayFlow API error shape, without token diagnostics. */
public final class SecurityErrors {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private SecurityErrors() {}

    public static AuthenticationEntryPoint unauthorized() {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(request, response, 401, "Unauthorized", "Bearer authentication is required");
        };
    }

    public static AccessDeniedHandler forbidden() {
        return (request, response, exception) ->
                write(request, response, 403, "Forbidden", "Access is denied");
    }

    private static void write(HttpServletRequest request, HttpServletResponse response,
                              int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(JSON.writeValueAsString(new ErrorResponse(
                Instant.now(), status, error, message, request.getRequestURI(), List.of())));
    }

    private record ErrorResponse(Instant timestamp, int status, String error, String message,
                                 String path, List<String> details) {}
}
