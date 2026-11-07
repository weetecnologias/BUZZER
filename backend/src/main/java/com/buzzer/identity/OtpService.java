package com.buzzer.identity;

import com.buzzer.common.BusinessException;
import com.buzzer.common.BuzzerProperties;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Hashing;
import com.buzzer.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Login OTPs: 6 digits, hashed with a server-side pepper, short-lived, single-use,
 * attempt-limited and rate-limited per phone number (database-backed, so limits hold across instances).
 */
@Service
public class OtpService {
    public record Issued(UUID challengeId, Instant expiresAt, String devCode) {}

    private record Challenge(UUID id, String codeHash, OffsetDateTime expiresAt, int attempts, OffsetDateTime consumedAt) {}

    private final JdbcClient jdbc;
    private final BuzzerProperties props;
    private final Clock clock;
    private final NotificationService notifications;

    public OtpService(JdbcClient jdbc, BuzzerProperties props, Clock clock, NotificationService notifications) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.notifications = notifications;
    }

    @Transactional
    public Issued issue(String phone, String purpose, boolean deliver) {
        Integer recent = jdbc.sql("SELECT count(*) FROM otp_challenges WHERE phone = :p AND created_at > now() - interval '1 hour'")
                .param("p", phone).query(Integer.class).single();
        if (recent >= props.otp().maxRequestsPerHour()) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "Too many OTP requests. Please try again later.");
        }
        // Invalidate earlier unused codes for the same phone/purpose.
        jdbc.sql("UPDATE otp_challenges SET consumed_at = now() WHERE phone = :p AND purpose = :pu AND consumed_at IS NULL")
                .param("p", phone).param("pu", purpose).update();

        String code = Hashing.numericCode(6);
        Instant expires = clock.instant().plusSeconds(props.otp().ttlSeconds());
        UUID id = jdbc.sql("""
                INSERT INTO otp_challenges(phone, purpose, code_hash, expires_at) VALUES (:p, :pu, :h, :e) RETURNING id""")
                .param("p", phone).param("pu", purpose)
                .param("h", Hashing.otpHash(props.otp().pepper(), phone + ":" + purpose, code))
                .param("e", OffsetDateTime.ofInstant(expires, ZoneOffset.UTC))
                .query(UUID.class).single();
        if (deliver) {
            notifications.enqueueSms(phone, "OTP_LOGIN",
                    Map.of("message", "Your BUZZER verification code is " + code + ". It expires in "
                            + (props.otp().ttlSeconds() / 60) + " minutes. Do not share it with anyone."));
        }
        return new Issued(id, expires, props.otp().exposeCodeInResponse() && deliver ? code : null);
    }

    /** Verifies and consumes the latest OTP for the phone. Throws on any failure. */
    @Transactional(noRollbackFor = BusinessException.class)
    public void verify(String phone, String purpose, String code) {
        Challenge c = jdbc.sql("""
                SELECT id, code_hash, expires_at, attempts, consumed_at FROM otp_challenges
                WHERE phone = :p AND purpose = :pu ORDER BY created_at DESC LIMIT 1 FOR UPDATE""")
                .param("p", phone).param("pu", purpose).query(Challenge.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.OTP_INVALID, "Please request a new OTP"));
        if (c.consumedAt() != null) {
            throw new BusinessException(ErrorCode.OTP_INVALID, "This OTP has already been used. Please request a new one.");
        }
        if (c.expiresAt().toInstant().isBefore(clock.instant())) {
            throw new BusinessException(ErrorCode.OTP_EXPIRED, "OTP expired. Please request a new one.");
        }
        if (c.attempts() >= props.otp().maxAttempts()) {
            throw new BusinessException(ErrorCode.OTP_TOO_MANY_ATTEMPTS, "Too many incorrect attempts. Please request a new OTP.");
        }
        String expected = Hashing.otpHash(props.otp().pepper(), phone + ":" + purpose, code == null ? "" : code.trim());
        if (!Hashing.constantTimeEquals(expected, c.codeHash())) {
            jdbc.sql("UPDATE otp_challenges SET attempts = attempts + 1 WHERE id = :id").param("id", c.id()).update();
            throw new BusinessException(ErrorCode.OTP_INVALID, "Incorrect OTP");
        }
        jdbc.sql("UPDATE otp_challenges SET consumed_at = now() WHERE id = :id").param("id", c.id()).update();
    }
}
