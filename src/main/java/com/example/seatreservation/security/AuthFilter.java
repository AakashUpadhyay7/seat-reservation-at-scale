package com.example.seatreservation.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
public class AuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    @Value("${app.auth.secret}")
    private String secret;

    @Value("${app.auth.admin-token}")
    private String adminToken;

    public static final String USER_ATTRIBUTE = "AUTH_USER";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws IOException, javax.servlet.ServletException {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);

        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        AuthUser user = authenticate(auth);
        if (user != null) request.setAttribute(USER_ATTRIBUTE, user);

        long started = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMicros = (System.nanoTime() - started) / 1_000;
            log.info("request method={} path={} status={} duration_us={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMicros);
            MDC.remove("requestId");
        }
    }

    private AuthUser authenticate(String header) {
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7).trim();
        if (token.equals(adminToken)) return new AuthUser("admin", true);

        // Assignment-friendly deterministic token format:
        // Bearer user:<userId>:<secret>
        String prefix = "user:";
        String suffix = ":" + secret;
        if (token.startsWith(prefix) && token.endsWith(suffix)) {
            String userId = token.substring(prefix.length(), token.length() - suffix.length());
            if (!userId.isBlank() && !userId.contains(":")) return new AuthUser(userId, false);
        }
        return null;
    }
}
