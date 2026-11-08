package com.buzzer.marketplace;

import com.buzzer.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public Shop & Buy browsing (no sign-in needed). Location: lat/lng, or a locality id. */
@RestController
@RequestMapping("/api/v1/marketplace")
public class MarketplaceController {
    private final MarketplaceService marketplace;

    public MarketplaceController(MarketplaceService marketplace) {
        this.marketplace = marketplace;
    }

    @GetMapping("/shops")
    public ApiResponse<List<Map<String, Object>>> shops(@RequestParam(required = false) BigDecimal lat, @RequestParam(required = false) BigDecimal lng,
                                                        @RequestParam(required = false) UUID localityId, @RequestParam(required = false) UUID categoryId,
                                                        @RequestParam(required = false) String q, @RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.of(marketplace.shops(marketplace.resolve(lat, lng, localityId), categoryId, q, limit));
    }

    @GetMapping("/shops/{id}")
    public ApiResponse<Map<String, Object>> shop(@PathVariable UUID id, @RequestParam(required = false) BigDecimal lat,
                                                 @RequestParam(required = false) BigDecimal lng, @RequestParam(required = false) UUID localityId) {
        return ApiResponse.of(marketplace.shop(id, marketplace.resolve(lat, lng, localityId)));
    }

    @GetMapping("/products")
    public ApiResponse<Map<String, Object>> products(@RequestParam(required = false) String q, @RequestParam(required = false) UUID categoryId,
                                                     @RequestParam(required = false) UUID subcategoryId, @RequestParam(required = false) UUID shopId,
                                                     @RequestParam(required = false) BigDecimal lat, @RequestParam(required = false) BigDecimal lng,
                                                     @RequestParam(required = false) UUID localityId,
                                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.of(marketplace.products(q, categoryId, subcategoryId, shopId, marketplace.resolve(lat, lng, localityId), page, size));
    }

    @GetMapping("/products/{id}")
    public ApiResponse<Map<String, Object>> product(@PathVariable UUID id, @RequestParam(required = false) BigDecimal lat,
                                                    @RequestParam(required = false) BigDecimal lng, @RequestParam(required = false) UUID localityId) {
        return ApiResponse.of(marketplace.product(id, marketplace.resolve(lat, lng, localityId)));
    }
}
