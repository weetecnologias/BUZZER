package com.buzzer.identity;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.PhoneNumbers;
import com.buzzer.customer.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {
    public record OtpRequested(String phoneMasked, Instant expiresAt, String devCode) {}

    public record LoginResult(TokenService.Tokens tokens, UUID userId, String fullName, String phone, Set<Role> roles, boolean newUser) {}

    private static final String LOGIN = "LOGIN";

    private final OtpService otp;
    private final TokenService tokens;
    private final UserRepository users;
    private final CustomerService customers;
    private final AuditService audit;

    public AuthService(OtpService otp, TokenService tokens, UserRepository users, CustomerService customers, AuditService audit) {
        this.otp = otp;
        this.tokens = tokens;
        this.users = users;
        this.customers = customers;
        this.audit = audit;
    }

    public OtpRequested requestOtp(String rawPhone, AppType app) {
        String phone = PhoneNumbers.normalise(rawPhone);
        boolean deliver = app.allowsSelfRegistration() || users.findByPhone(phone)
                .filter(u -> "ACTIVE".equals(u.status()))
                .map(u -> app.permits(users.roles(u.id()))).orElse(false);
        // Staff apps: we respond identically whether or not the number is registered (no account enumeration),
        // but only registered staff actually receive a code.
        OtpService.Issued issued = otp.issue(phone, LOGIN + ":" + app.name(), deliver);
        return new OtpRequested(PhoneNumbers.mask(phone), issued.expiresAt(), issued.devCode());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResult verifyOtp(String rawPhone, String code, AppType app, String fullName, String userAgent) {
        String phone = PhoneNumbers.normalise(rawPhone);
        otp.verify(phone, LOGIN + ":" + app.name(), code);
        return completeLogin(phone, app, fullName, userAgent);
    }

    private LoginResult completeLogin(String phone, AppType app, String fullName, String userAgent) {
        boolean created = false;
        var existing = users.findByPhone(phone);
        UserRepository.User user;
        if (existing.isPresent()) {
            user = existing.get();
        } else {
            if (!app.allowsSelfRegistration()) {
                throw new BusinessException(ErrorCode.ROLE_NOT_ALLOWED, "This number is not registered for this app");
            }
            user = users.create(phone, fullName);
            created = true;
        }
        if (!"ACTIVE".equals(user.status())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "This account is not active. Please contact BUZZER support.");
        }
        if (app == AppType.CUSTOMER) {
            Set<Role> current = users.roles(user.id());
            if (!current.contains(Role.CUSTOMER)) {
                users.grantRole(user.id(), Role.CUSTOMER, null);
                // No agent yet: the app's welcome screen offers "Subscribe for a dedicated agent" or "Explore".
                customers.ensureProfile(user.id(), null);
                created = true;
            }
        }
        Set<Role> roles = users.roles(user.id());
        if (!app.permits(roles)) {
            throw new BusinessException(ErrorCode.ROLE_NOT_ALLOWED, "You do not have access to this app");
        }
        audit.record(user.id(), app.name(), "LOGIN", "USER", user.id(), Map.of("app", app.name(), "newUser", created));
        var t = tokens.issue(user.id(), app, roles, userAgent);
        var fresh = users.findById(user.id()).orElseThrow();
        return new LoginResult(t, user.id(), fresh.fullName(), fresh.phone(), roles, created);
    }

    @Transactional
    public TokenService.Tokens refresh(String refreshToken) {
        return tokens.refresh(refreshToken, users);
    }

    public void logout(String refreshToken) {
        tokens.revoke(refreshToken);
    }
}
