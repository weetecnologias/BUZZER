package com.buzzer.marketplace;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.ExcelSupport;
import com.buzzer.identity.CurrentUser;
import com.buzzer.partner.PartnerService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Partner app: shop profile, partner types, locations, product listings and Excel upload. */
@RestController
@RequestMapping("/api/v1/partner")
@PreAuthorize("hasRole('PARTNER')")
public class PartnerShopController {
    public record RoleInput(String role) {}

    private final PartnerShopService shops;
    private final PartnerService partners;
    private final JdbcClient jdbc;

    public PartnerShopController(PartnerShopService shops, PartnerService partners, JdbcClient jdbc) {
        this.shops = shops;
        this.partners = partners;
        this.jdbc = jdbc;
    }

    private UUID me() {
        return partners.partnerIdFor(CurrentUser.id());
    }

    /** Shop overview: partner types, locations and listing / order counts. */
    @GetMapping("/shop")
    public ApiResponse<Map<String, Object>> shop() {
        UUID p = me();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roles", shops.roles(p));
        out.put("is_shop", shops.isShop(p));
        out.put("locations", shops.locations(p));
        out.put("counts", jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'ACTIVE') AS active_listings, count(*) FILTER (WHERE status = 'PENDING') AS pending_listings,
                       count(*) FILTER (WHERE status = 'ACTIVE' AND stock_status = 'OUT_OF_STOCK') AS out_of_stock,
                       (SELECT count(*) FROM orders o WHERE o.partner_id = :p AND o.status IN ('PLACED','PAID')) AS new_orders,
                       (SELECT count(*) FROM orders o WHERE o.partner_id = :p AND o.status IN ('VENDOR_CONFIRMED','READY_FOR_PICKUP','OUT_FOR_DELIVERY')) AS active_orders
                FROM vendor_listings WHERE partner_id = :p""").param("p", p).query().singleRow());
        return ApiResponse.of(out);
    }

    @PostMapping("/roles")
    public ApiResponse<List<Map<String, Object>>> requestRole(@RequestBody RoleInput in) {
        UUID p = me();
        shops.requestRole(p, in.role() == null ? null : in.role().toUpperCase(), CurrentUser.id());
        return ApiResponse.of(shops.roles(p));
    }

    @GetMapping("/locations")
    public ApiResponse<List<Map<String, Object>>> locations() {
        return ApiResponse.of(shops.locations(me()));
    }

    @PostMapping("/locations")
    public ApiResponse<Map<String, Object>> addLocation(@RequestBody PartnerShopService.LocationInput in) {
        return ApiResponse.of(shops.saveLocation(me(), null, in, CurrentUser.id()));
    }

    @PutMapping("/locations/{id}")
    public ApiResponse<Map<String, Object>> updateLocation(@PathVariable UUID id, @RequestBody PartnerShopService.LocationInput in) {
        return ApiResponse.of(shops.saveLocation(me(), id, in, CurrentUser.id()));
    }

    @GetMapping("/catalogue/products")
    public ApiResponse<List<Map<String, Object>>> catalogue(@RequestParam(required = false) String q, @RequestParam(required = false) UUID subcategoryId) {
        return ApiResponse.of(shops.catalogue(me(), q, subcategoryId));
    }

    @GetMapping("/listings")
    public ApiResponse<List<Map<String, Object>>> listings(@RequestParam(required = false) String status) {
        return ApiResponse.of(shops.listings(me(), status));
    }

    /** Create or update a listing (price, MRP, stock status, minimum / bulk quantities, pause). */
    @PostMapping("/listings")
    public ApiResponse<Map<String, Object>> saveListing(@RequestBody PartnerShopService.ListingInput in) {
        return ApiResponse.of(shops.saveListing(me(), in, CurrentUser.id()));
    }

    @PostMapping("/products")
    public ApiResponse<Map<String, Object>> proposeProduct(@RequestBody PartnerShopService.ProductProposal in) {
        return ApiResponse.of(shops.proposeProduct(me(), in, CurrentUser.id()));
    }

    @GetMapping("/listings/excel")
    public ResponseEntity<byte[]> excelTemplate() {
        return ExcelSupport.download("buzzer-my-products.xlsx", shops.excelTemplate(me()));
    }

    @PostMapping(value = "/listings/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> excelUpload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.of(shops.excelUpload(me(), file, CurrentUser.id()));
    }
}
