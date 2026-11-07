package com.buzzer.identity;

import com.buzzer.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP fixed-window rate limiting for authentication endpoints (first line of defence).
 * Per-phone OTP limits are additionally enforced in the database by {@link OtpService}, which works
 * across multiple backend instances. For multi-instance production, back this filter with Redis.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final int authLimitPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private record Window(long minute, AtomicInteger count) {}

    public RateLimitFilter(@org.springframework.beans.factory.annotation.Value("${buzzer.security.auth-rate-limit-per-minute:30}") int authLimitPerMinute) {
        this.authLimitPerMinute = authLimitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long minute = System.currentTimeMillis() / 60_000;
        String key = request.getRemoteAddr();
        Window w = windows.compute(key, (k, existing) ->
                existing == null || existing.minute() != minute ? new Window(minute, new AtomicInteger()) : existing);
        if (w.count().incrementAndGet() > authLimitPerMinute) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"code\":\"" + ErrorCode.RATE_LIMITED.name()
                    + "\",\"message\":\"Too many requests. Please wait a minute and try again.\",\"details\":{}}}");
            return;
        }
        if (windows.size() > 50_000) windows.entrySet().removeIf(e -> e.getValue().minute() != minute);
        chain.doFilter(request, response);
    }
}
