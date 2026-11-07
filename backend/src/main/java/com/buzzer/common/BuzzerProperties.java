package com.buzzer.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * All environment-specific configuration. Secrets are supplied through environment variables
 * (see application.yml / .env.example) and are never hard-coded.
 */
@ConfigurationProperties(prefix = "buzzer")
public record BuzzerProperties(Security security, Otp otp, Payments payments, Cors cors) {

    public record Security(String jwtSecret, int accessTokenMinutes, int refreshTokenDays, String issuer) {}

    /**
     * @param exposeCodeInResponse DEVELOPMENT ONLY - returns the OTP in the API response so the
     *                             apps can be exercised without an SMS provider. Must be false in production.
     */
    public record Otp(String pepper, int ttlSeconds, int maxAttempts, int maxRequestsPerHour, boolean exposeCodeInResponse) {}

    public record Payments(String provider, String webhookSecret, boolean testMode) {}

    public record Cors(List<String> allowedOrigins) {}
}
