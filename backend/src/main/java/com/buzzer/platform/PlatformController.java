package com.buzzer.platform;

import com.buzzer.common.ApiResponse;
import com.buzzer.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Business verticals, featured shops and universal search (public), plus the admin on/off switches. */
@RestController
public class PlatformController {
    public record VerticalSwitch(@NotNull Boolean enabled) {}

    private final PlatformService platform;

    public PlatformController(PlatformService platform) {
        this.platform = platform;
    }

    /** Enabled verticals in display order (drives the customer home cards and Explore tabs). */
    @GetMapping("/api/v1/platform/verticals")
    public ApiResponse<List<Map<String, Object>>> verticals() {
        return ApiResponse.of(platform.verticals(false));
    }

    @GetMapping("/api/v1/platform/featured-shops")
    public ApiResponse<List<Map<String, Object>>> featuredShops(@RequestParam(defaultValue = "8") int limit) {
        return ApiResponse.of(platform.featuredShops(limit));
    }

    /** "Search products, shops, services or tell us what you need..." - results plus one suggested workflow (a link, never an action). */
    @GetMapping("/api/v1/search")
    public ApiResponse<Map<String, Object>> search(@RequestParam String q) {
        return ApiResponse.of(platform.search(q));
    }

    @GetMapping("/api/v1/admin/platform/verticals")
    public ApiResponse<List<Map<String, Object>>> adminVerticals() {
        return ApiResponse.of(platform.verticals(true));
    }

    @PutMapping("/api/v1/admin/platform/verticals/{code}")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> setVertical(@PathVariable String code, @Valid @RequestBody VerticalSwitch in) {
        return ApiResponse.of(platform.setEnabled(code.toUpperCase(), in.enabled(), CurrentUser.id()));
    }
}
