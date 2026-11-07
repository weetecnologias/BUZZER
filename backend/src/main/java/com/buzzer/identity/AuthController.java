package com.buzzer.identity;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    public record OtpRequest(@NotBlank String phone, @NotNull AppType app) {}

    public record VerifyRequest(@NotBlank String phone, @NotBlank @Size(min = 4, max = 8) String code, @NotNull AppType app,
                                @Size(max = 160) String fullName) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    private final AuthService auth;
    private final UserRepository users;

    public AuthController(AuthService auth, UserRepository users) {
        this.auth = auth;
        this.users = users;
    }

    @PostMapping("/otp/request")
    public ApiResponse<AuthService.OtpRequested> requestOtp(@Valid @RequestBody OtpRequest req) {
        return ApiResponse.of(auth.requestOtp(req.phone(), req.app()));
    }

    @PostMapping("/otp/verify")
    public ApiResponse<AuthService.LoginResult> verify(@Valid @RequestBody VerifyRequest req,
                                                       @RequestHeader(value = "User-Agent", required = false) String ua) {
        return ApiResponse.of(auth.verifyOtp(req.phone(), req.code(), req.app(), req.fullName(), ua));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenService.Tokens> refresh(@Valid @RequestBody RefreshRequest req) {
        return ApiResponse.of(auth.refresh(req.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
        return ApiResponse.of(Map.of("logged_out", true));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        var u = users.findById(CurrentUser.id()).orElseThrow(() -> BusinessException.notFound("User"));
        return ApiResponse.of(Map.of(
                "id", u.id(), "phone", u.phone(),
                "full_name", u.fullName() == null ? "" : u.fullName(),
                "email", u.email() == null ? "" : u.email(),
                "roles", users.roles(u.id())));
    }
}
