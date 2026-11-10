package com.kitdesk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitdesk.api.ApiError;
import com.kitdesk.service.Caller;
import com.kitdesk.service.ErrorCatalog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication: every request except login, the health checks and the docs must carry a valid
 * {@code Authorization: Bearer <token>} header, or it gets 401 UNAUTHORIZED before reaching any
 * controller. The verified caller is stored on the request for the controllers to pass on to
 * the services. Role and ownership rules are not checked here; the services check them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)   // right after the correlation-ID filter
public class AuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTRIBUTE = "kitdesk.caller";

    private final JwtTokens tokens;
    private final ObjectMapper json;

    public AuthFilter(JwtTokens tokens, ObjectMapper json) {
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/auth/login")
                || path.startsWith("/health/")
                || path.equals("/openapi.yaml")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<Caller> caller = bearerToken(request).flatMap(tokens::verify);
        if (caller.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ApiError.of(ErrorCatalog.UNAUTHORIZED,
                    "A valid bearer token is required. Log in again to get a new one."));
            return;
        }
        request.setAttribute(CALLER_ATTRIBUTE, caller.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
