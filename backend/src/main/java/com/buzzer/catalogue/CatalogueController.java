package com.buzzer.catalogue;

import com.buzzer.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public, read-only catalogue browsing used by the Customer, Agent and Partner apps. */
@RestController
@RequestMapping("/api/v1/catalogue")
public class CatalogueController {
    private final CatalogueService catalogue;

    public CatalogueController(CatalogueService catalogue) {
        this.catalogue = catalogue;
    }

    @GetMapping("/categories")
    public ApiResponse<List<Map<String, Object>>> categories(@RequestParam(required = false) UUID cityId,
                                                            @RequestParam(required = false) String vertical) {
        return ApiResponse.of(catalogue.categories(cityId, vertical));
    }

    @GetMapping("/categories/{id}")
    public ApiResponse<Map<String, Object>> category(@PathVariable UUID id) {
        return ApiResponse.of(catalogue.category(id));
    }

    @GetMapping("/categories/{id}/subcategories")
    public ApiResponse<List<Map<String, Object>>> subcategories(@PathVariable UUID id) {
        return ApiResponse.of(catalogue.subcategories(id));
    }

    @GetMapping("/categories/{id}/form")
    public ApiResponse<Map<String, Object>> categoryForm(@PathVariable UUID id) {
        return ApiResponse.of(catalogue.categoryForm(id));
    }

    @GetMapping("/categories/{id}/services")
    public ApiResponse<List<Map<String, Object>>> servicesInCategory(@PathVariable UUID id) {
        return ApiResponse.of(catalogue.servicesInCategory(id));
    }

    @GetMapping("/subcategories/{id}/services")
    public ApiResponse<List<Map<String, Object>>> services(@PathVariable UUID id) {
        return ApiResponse.of(catalogue.services(id));
    }

    @GetMapping("/services/{id}")
    public ApiResponse<Map<String, Object>> service(@PathVariable UUID id) {
        return ApiResponse.of(catalogue.serviceDetail(id));
    }

    @GetMapping("/deals")
    public ApiResponse<List<Map<String, Object>>> deals(@RequestParam(defaultValue = "12") int limit) {
        return ApiResponse.of(catalogue.deals(limit));
    }

    @GetMapping("/search")
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.of(catalogue.search(q, limit));
    }
}
