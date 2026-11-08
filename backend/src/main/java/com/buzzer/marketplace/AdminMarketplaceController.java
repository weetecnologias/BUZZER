package com.buzzer.marketplace;

import com.buzzer.common.ApiResponse;
import com.buzzer.identity.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin: vendor management, product catalogue moderation, listing review, brands and RFQ invite rules. Changes need ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/marketplace")
public class AdminMarketplaceController {
    public record DecisionInput(String decision, String note) {}

    private final AdminMarketplaceService admin;

    public AdminMarketplaceController(AdminMarketplaceService admin) {
        this.admin = admin;
    }

    @GetMapping("/shops")
    public ApiResponse<List<Map<String, Object>>> shops(@RequestParam(required = false) String roleStatus, @RequestParam(required = false) String q) {
        return ApiResponse.of(admin.shops(roleStatus, q));
    }

    @PostMapping("/shops/{partnerId}/roles/{role}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> reviewRole(@PathVariable UUID partnerId, @PathVariable String role, @RequestBody DecisionInput in) {
        admin.reviewRole(partnerId, role.toUpperCase(), in.decision() == null ? "" : in.decision().toUpperCase(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("role", role.toUpperCase(), "status", in.decision().toUpperCase()));
    }

    @GetMapping("/products")
    public ApiResponse<List<Map<String, Object>>> products(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) UUID subcategoryId) {
        return ApiResponse.of(admin.products(status, q, subcategoryId));
    }

    @PostMapping("/products")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> createProduct(@RequestBody AdminMarketplaceService.ProductInput in) {
        return ApiResponse.of(admin.saveProduct(null, in, CurrentUser.id()));
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> updateProduct(@PathVariable UUID id, @RequestBody AdminMarketplaceService.ProductInput in) {
        return ApiResponse.of(admin.saveProduct(id, in, CurrentUser.id()));
    }

    @PostMapping("/products/{id}/variants")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> addVariant(@PathVariable UUID id, @RequestBody AdminMarketplaceService.VariantInput in) {
        return ApiResponse.of(admin.addVariant(id, in, CurrentUser.id()));
    }

    @PostMapping("/products/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> reviewProduct(@PathVariable UUID id, @RequestBody DecisionInput in) {
        admin.reviewProduct(id, in.decision() == null ? "" : in.decision().toUpperCase(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("decision", in.decision().toUpperCase()));
    }

    @GetMapping("/listings")
    public ApiResponse<List<Map<String, Object>>> listings(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return ApiResponse.of(admin.listings(status, q));
    }

    @PostMapping("/listings/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> reviewListing(@PathVariable UUID id, @RequestBody DecisionInput in) {
        admin.reviewListing(id, in.decision() == null ? "" : in.decision().toUpperCase(), in.note(), CurrentUser.id());
        return ApiResponse.of(Map.of("status", in.decision().toUpperCase()));
    }

    @GetMapping("/brands")
    public ApiResponse<List<Map<String, Object>>> brands() {
        return ApiResponse.of(admin.brands());
    }

    @GetMapping("/rfq-rules")
    public ApiResponse<List<Map<String, Object>>> rules() {
        return ApiResponse.of(admin.rules());
    }

    @PostMapping("/rfq-rules")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> saveRule(@RequestBody AdminMarketplaceService.RuleInput in) {
        return ApiResponse.of(admin.saveRule(in, CurrentUser.id()));
    }

    @DeleteMapping("/rfq-rules/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> deleteRule(@PathVariable UUID id) {
        return ApiResponse.of(admin.deleteRule(id, CurrentUser.id()));
    }
}
