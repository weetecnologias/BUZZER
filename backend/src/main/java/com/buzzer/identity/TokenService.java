package com.buzzer.identity;

import com.buzzer.common.BusinessException;
import com.buzzer.common.BuzzerProperties;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.Hashing;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Issues short-lived access JWTs and rotating, revocable refresh tokens (stored hashed). */
@Service
public class TokenService {
    public record Tokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt) {}

    private record SessionRow(UUID id, UUID userId, String app, OffsetDateTime expiresAt, OffsetDateTime revokedAt) {}

    private final JwtEncoder encoder;
    private final JdbcClient jdbc;
    private final BuzzerProperties props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JdbcClient jdbc, BuzzerProperties props, Clock clock) {
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public Tokens issue(UUID userId, AppType app, Set<Role> roles, String userAgent) {
        String refresh = Hashing.randomToken(32);
        Instant refreshExp = clock.instant().plus(props.security().refreshTokenDays(), ChronoUnit.DAYS);
        jdbc.sql("""
                INSERT INTO auth_sessions(user_id, app, refresh_token_hash, expires_at, user_agent)
                VALUES (:u, :a, :h, :e, :ua)""")
                .param("u", userId).param("a", app.name()).param("h", Hashing.sha256(refresh))
                .param("e", OffsetDateTime.ofInstant(refreshExp, ZoneOffset.UTC))
                .param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())))
                .update();
        return access(userId, app, roles, refresh, refreshExp);
    }

    private Tokens access(UUID userId, AppType app, Set<Role> roles, String refresh, Instant refreshExp) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.security().accessTokenMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.security().issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(exp)
                .claim("app", app.name())
                .claim("roles", roles.stream().map(Enum::name).sorted().toList())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Tokens(token, exp, refresh, refreshExp);
    }

    /** Rotates the refresh token: the old one is revoked and a new pair is returned. */
    public Tokens refresh(String refreshToken, UserRepository users) {
        SessionRow s = jdbc.sql("""
                SELECT id, user_id, app, expires_at, revoked_at FROM auth_sessions WHERE refresh_token_hash = :h FOR UPDATE""")
                .param("h", Hashing.sha256(refreshToken == null ? "" : refreshToken)).query(SessionRow.class).optional()
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_INVALID, "Session expired. Please log in again."));
        if (s.revokedAt() != null || s.expiresAt().toInstant().isBefore(clock.instant())) {
            throw new BusinessException(ErrorCode.SESSION_INVALID, "Session expired. Please log in again.");
        }
        var user = users.findById(s.userId()).orElseThrow();
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(ErrorCode.SESSION_INVALID, "Account is not active");
        }
        AppType app = AppType.valueOf(s.app());
        Set<Role> roles = users.roles(s.userId());
        if (!app.permits(roles)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ALLOWED, "Access to this app has been withdrawn");
        }
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE id = :id").param("id", s.id()).update();
        return issue(s.userId(), app, roles, null);
    }

    public void revoke(String refreshToken) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE refresh_token_hash = :h AND revoked_at IS NULL")
                .param("h", Hashing.sha256(refreshToken == null ? "" : refreshToken)).update();
    }

    public void revokeAll(UUID userId) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL").param("u", userId).update();
    }

    public List<String> appNames() {
        return List.of(AppType.values()).stream().map(Enum::name).toList();
    }
}
