package com.buzzer.marketplace;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import com.buzzer.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Admin control centre for the marketplace: vendor types, product catalogue moderation, listing review and RFQ invite rules. */
@Service
public class AdminMarketplaceService {
    public record ProductInput(UUID subcategoryId, String code, String name, String brand, String description, String unit,
                               Map<String, String> specs, BigDecimal taxRatePercent, String hsnCode, String imageUrl, String status) {}

    public record VariantInput(String name, String sku, Map<String, String> attributes) {}

    public record RuleInput(UUID categoryId, UUID cityId, UUID localityId, Integer inviteCount) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final PartnerShopService shops;
    private final Json json;

    public AdminMarketplaceService(JdbcClient jdbc, AuditService audit, NotificationService notifications, PartnerShopService shops, Json json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.shops = shops;
        this.json = json;
    }

    // ----- Vendors -----------------------------------------------------------------------------------

    public List<Map<String, Object>> shops(String roleStatus, String q) {
        return jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.contact_phone, p.registration_status, p.kyc_status, p.availability,
                       p.rating_avg, c.name AS city_name,
                       (SELECT json_agg(json_build_object('role', r.role, 'status', r.status) ORDER BY r.role)::text FROM partner_roles r
                        WHERE r.partner_id = p.id) AS roles,
                       (SELECT count(*) FROM partner_locations pl WHERE pl.partner_id = p.id AND pl.active) AS locations,
                       (SELECT count(*) FROM vendor_listings l WHERE l.partner_id = p.id AND l.status = 'ACTIVE') AS active_listings,
                       (SELECT count(*) FROM vendor_listings l WHERE l.partner_id = p.id AND l.status = 'PENDING') AS pending_listings,
                       (SELECT count(*) FROM orders o WHERE o.partner_id = p.id) AS orders,
                       (SELECT count(*) FROM orders o WHERE o.partner_id = p.id AND o.status = 'COMPLETED') AS completed_orders
                FROM partners p JOIN cities c ON c.id = p.city_id
                WHERE EXISTS (SELECT 1 FROM partner_roles r WHERE r.partner_id = p.id AND r.role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR')
                              AND (CAST(:rs AS text) IS NULL OR r.status = CAST(:rs AS text)))
                  AND (CAST(:q AS text) IS NULL OR p.display_name ILIKE '%' || CAST(:q AS text) || '%' OR p.contact_phone ILIKE '%' || CAST(:q AS text) || '%')
                ORDER BY p.created_at DESC LIMIT 500""").param("rs", MarketplaceService.blankToNull(roleStatus))
                .param("q", MarketplaceService.blankToNull(q)).query().listOfRows().stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("roles", r.get("roles") == null ? List.of() : json.tree((String) r.get("roles")));
                    return m;
                }).toList();
    }

    @Transactional
    public void reviewRole(UUID partnerId, String role, String decision, String note, UUID admin) {
        if (!PartnerShopService.ROLES.contains(role)) throw BusinessException.invalid("Unknown partner type");
        if (!Set.of("APPROVED", "REJECTED", "SUSPENDED").contains(decision)) throw BusinessException.invalid("Decision must be APPROVED, REJECTED or SUSPENDED");
        int n = jdbc.sql("""
                INSERT INTO partner_roles(partner_id, role, status, reviewed_by, reviewed_at, review_note) VALUES (:p, :r, :d, :a, now(), :n)
                ON CONFLICT (partner_id, role) DO UPDATE SET status = :d, reviewed_by = :a, reviewed_at = now(), review_note = :n""")
                .param("p", partnerId).param("r", role).param("d", decision).param("a", admin).param("n", note).update();
        if (n == 0) throw BusinessException.notFound("Partner");
        audit.record(admin, "ADMIN", "PARTNER_ROLE_" + decision, "PARTNER", partnerId, Map.of("role", role, "note", note == null ? "" : note));
        notifyPartner(partnerId, "Partner type " + role.replace('_', ' ').toLowerCase() + " " + decision.toLowerCase(),
                note == null ? "BUZZER reviewed your partner type." : note);
    }

    // ----- Products ----------------------------------------------------------------------------------

    public List<Map<String, Object>> products(String status, String q, UUID subcategoryId) {
        return jdbc.sql("""
                SELECT pr.id, pr.code, pr.name, pr.unit, pr.status, pr.tax_rate_percent, pr.description, pr.specs::text AS specs, pr.review_note,
                       pr.created_at, b.name AS brand, s.id AS subcategory_id, s.name AS subcategory_name, c.name AS category_name,
                       pp.display_name AS proposed_by,
                       (SELECT count(*) FROM product_variants v WHERE v.product_id = pr.id AND v.active) AS variants,
                       (SELECT count(*) FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id WHERE v.product_id = pr.id AND l.status = 'ACTIVE') AS active_listings,
                       (SELECT min(l.price) FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id WHERE v.product_id = pr.id AND l.status = 'ACTIVE') AS min_price
                FROM products pr JOIN subcategories s ON s.id = pr.subcategory_id JOIN categories c ON c.id = s.category_id
                LEFT JOIN brands b ON b.id = pr.brand_id LEFT JOIN partners pp ON pp.id = pr.proposed_by_partner_id
                WHERE (CAST(:s AS text) IS NULL OR pr.status = CAST(:s AS text))
                  AND (CAST(:sub AS uuid) IS NULL OR s.id = CAST(:sub AS uuid))
                  AND (CAST(:q AS text) IS NULL OR pr.name ILIKE '%' || CAST(:q AS text) || '%' OR pr.code ILIKE '%' || CAST(:q AS text) || '%'
                       OR b.name ILIKE '%' || CAST(:q AS text) || '%')
                ORDER BY pr.status = 'PENDING' DESC, c.sort_order, s.sort_order, pr.sort_order, pr.name LIMIT 1000""")
                .param("s", MarketplaceService.blankToNull(status)).param("sub", subcategoryId).param("q", MarketplaceService.blankToNull(q))
                .query().listOfRows().stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.put("specs", json.map((String) r.get("specs")));
                    return m;
                }).toList();
    }

    @Transactional
    public Map<String, Object> saveProduct(UUID productId, ProductInput in, UUID admin) {
        if (in == null) throw BusinessException.invalid("Product details are required");
        String name = in.name() == null ? null : in.name().trim();
        if (name == null || name.length() < 3 || name.length() > 200) throw BusinessException.invalid("Product name must be 3-200 characters");
        if (in.subcategoryId() == null) throw BusinessException.invalid("Choose a subcategory");
        Integer marketplace = jdbc.sql("""
                SELECT count(*) FROM subcategories s JOIN categories c ON c.id = s.category_id WHERE s.id = :s AND c.vertical = 'MARKETPLACE'""")
                .param("s", in.subcategoryId()).query(Integer.class).single();
        if (marketplace == 0) throw BusinessException.invalid("Products belong to a Shop & Buy (marketplace) subcategory");
        BigDecimal tax = in.taxRatePercent() == null ? BigDecimal.valueOf(18) : in.taxRatePercent();
        if (tax.signum() < 0 || tax.compareTo(BigDecimal.valueOf(40)) > 0) throw BusinessException.invalid("Tax rate must be 0-40%");
        String status = in.status() == null ? "ACTIVE" : in.status().toUpperCase();
        if (!Set.of("ACTIVE", "INACTIVE", "PENDING", "REJECTED").contains(status)) throw BusinessException.invalid("Invalid status");
        UUID brandId = shops.brand(in.brand());
        String specs = json.write(in.specs() == null ? Map.of() : in.specs());
        UUID id;
        if (productId == null) {
            String code = in.code() == null || in.code().isBlank() ? name.toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "")
                    : in.code().trim().toUpperCase();
            if (code.length() > 70) code = code.substring(0, 70);
            Integer taken = jdbc.sql("SELECT count(*) FROM products WHERE code = :c").param("c", code).query(Integer.class).single();
            if (taken > 0) throw BusinessException.invalid("Product code " + code + " already exists");
            id = jdbc.sql("""
                    INSERT INTO products(code, subcategory_id, brand_id, name, description, unit, specs, tax_rate_percent, hsn_code, image_url, status)
                    VALUES (:code, :s, :b, :n, :d, :u, CAST(:specs AS jsonb), :tax, :hsn, :img, :st) RETURNING id""")
                    .param("code", code).param("s", in.subcategoryId()).param("b", brandId).param("n", name).param("d", in.description())
                    .param("u", in.unit() == null || in.unit().isBlank() ? "each" : in.unit().trim()).param("specs", specs).param("tax", tax)
                    .param("hsn", in.hsnCode()).param("img", in.imageUrl()).param("st", status).query(UUID.class).single();
            jdbc.sql("INSERT INTO product_variants(product_id, name, sku) VALUES (:p, 'Standard', :sku)").param("p", id).param("sku", code).update();
        } else {
            int n = jdbc.sql("""
                    UPDATE products SET subcategory_id = :s, brand_id = :b, name = :n, description = :d, unit = :u, specs = CAST(:specs AS jsonb),
                      tax_rate_percent = :tax, hsn_code = :hsn, image_url = COALESCE(:img, image_url), status = :st, updated_at = now()
                    WHERE id = :id""").param("s", in.subcategoryId()).param("b", brandId).param("n", name).param("d", in.description())
                    .param("u", in.unit() == null || in.unit().isBlank() ? "each" : in.unit().trim()).param("specs", specs).param("tax", tax)
                    .param("hsn", in.hsnCode()).param("img", in.imageUrl()).param("st", status).param("id", productId).update();
            if (n == 0) throw BusinessException.notFound("Product");
            id = productId;
        }
        audit.record(admin, "ADMIN", productId == null ? "PRODUCT_CREATED" : "PRODUCT_UPDATED", "PRODUCT", id, Map.of("name", name, "status", status));
        return products(null, null, null).stream().filter(p -> id.equals(p.get("id"))).findFirst().orElseThrow();
    }

    @Transactional
    public Map<String, Object> addVariant(UUID productId, VariantInput in, UUID admin) {
        if (in == null || in.name() == null || in.name().isBlank() || in.name().length() > 120) throw BusinessException.invalid("Variant name is required");
        Integer exists = jdbc.sql("SELECT count(*) FROM products WHERE id = :p").param("p", productId).query(Integer.class).single();
        if (exists == 0) throw BusinessException.notFound("Product");
        String sku = in.sku() == null || in.sku().isBlank() ? null : in.sku().trim().toUpperCase();
        if (sku != null && jdbc.sql("SELECT count(*) FROM product_variants WHERE sku = :s").param("s", sku).query(Integer.class).single() > 0) {
            throw BusinessException.invalid("SKU " + sku + " already exists");
        }
        UUID id = jdbc.sql("""
                INSERT INTO product_variants(product_id, name, sku, attributes, sort_order)
                VALUES (:p, :n, :sku, CAST(:a AS jsonb), (SELECT COALESCE(max(sort_order), 0) + 1 FROM product_variants WHERE product_id = :p)) RETURNING id""")
                .param("p", productId).param("n", in.name().trim()).param("sku", sku)
                .param("a", json.write(in.attributes() == null ? Map.of() : in.attributes())).query(UUID.class).single();
        audit.record(admin, "ADMIN", "PRODUCT_VARIANT_ADDED", "PRODUCT", productId, Map.of("variant", in.name().trim()));
        return Map.of("id", id, "name", in.name().trim(), "sku", sku == null ? "" : sku);
    }

    /** Approving a shop's proposed product also puts that shop's listing for it live. */
    @Transactional
    public void reviewProduct(UUID productId, String decision, String note, UUID admin) {
        if (!Set.of("APPROVED", "REJECTED").contains(decision)) throw BusinessException.invalid("Decision must be APPROVED or REJECTED");
        record P(UUID proposedBy, String name) {}
        P p = jdbc.sql("""
                UPDATE products SET status = :s, review_note = :n, reviewed_by = :a, reviewed_at = now(), updated_at = now()
                WHERE id = :id RETURNING proposed_by_partner_id AS proposed_by, name""")
                .param("s", "APPROVED".equals(decision) ? "ACTIVE" : "REJECTED").param("n", note).param("a", admin).param("id", productId)
                .query(P.class).optional().orElseThrow(() -> BusinessException.notFound("Product"));
        if (p.proposedBy() != null) {
            jdbc.sql("""
                    UPDATE vendor_listings l SET status = :s, review_note = :n, reviewed_by = :a, reviewed_at = now(), updated_at = now()
                    FROM product_variants v WHERE v.id = l.variant_id AND v.product_id = :id AND l.partner_id = :p AND l.status = 'PENDING'""")
                    .param("s", "APPROVED".equals(decision) ? "ACTIVE" : "REJECTED").param("n", note).param("a", admin).param("id", productId)
                    .param("p", p.proposedBy()).update();
            notifyPartner(p.proposedBy(), "Product " + ("APPROVED".equals(decision) ? "approved" : "not approved") + ": " + p.name(),
                    note == null ? ("APPROVED".equals(decision) ? "Customers can now find it in your shop." : "Please contact BUZZER support.") : note);
        }
        audit.record(admin, "ADMIN", "PRODUCT_" + decision, "PRODUCT", productId, Map.of("note", note == null ? "" : note));
    }

    // ----- Listings -----------------------------------------------------------------------------------

    public List<Map<String, Object>> listings(String status, String q) {
        return jdbc.sql("""
                SELECT l.id, l.price, l.mrp, l.stock_status, l.stock_updated_at, l.moq, l.bulk_price, l.bulk_min_qty, l.lead_time_days, l.status,
                       l.review_note, l.created_at, l.updated_at, v.name AS variant_name, v.sku, pr.id AS product_id, pr.name AS product_name,
                       pr.status AS product_status, s.name AS subcategory_name, p.id AS partner_id, p.display_name AS shop_name,
                       EXISTS (SELECT 1 FROM partner_capabilities pc WHERE pc.partner_id = p.id AND pc.status = 'APPROVED'
                               AND pc.subcategory_id = pr.subcategory_id) AS capability_approved
                FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id
                JOIN subcategories s ON s.id = pr.subcategory_id JOIN partners p ON p.id = l.partner_id
                WHERE (CAST(:s AS text) IS NULL OR l.status = CAST(:s AS text))
                  AND (CAST(:q AS text) IS NULL OR pr.name ILIKE '%' || CAST(:q AS text) || '%' OR p.display_name ILIKE '%' || CAST(:q AS text) || '%')
                ORDER BY l.status = 'PENDING' DESC, l.updated_at DESC LIMIT 1000""")
                .param("s", MarketplaceService.blankToNull(status)).param("q", MarketplaceService.blankToNull(q)).query().listOfRows();
    }

    @Transactional
    public void reviewListing(UUID listingId, String decision, String note, UUID admin) {
        if (!Set.of("ACTIVE", "REJECTED", "PAUSED").contains(decision)) throw BusinessException.invalid("Decision must be ACTIVE, REJECTED or PAUSED");
        record L(UUID partnerId, String productName) {}
        L l = jdbc.sql("""
                UPDATE vendor_listings vl SET status = :d, review_note = :n, reviewed_by = :a, reviewed_at = now(), updated_at = now()
                FROM product_variants v JOIN products pr ON pr.id = v.product_id
                WHERE vl.id = :id AND v.id = vl.variant_id RETURNING vl.partner_id, pr.name AS product_name""")
                .param("d", decision).param("n", "PAUSED".equals(decision) ? "Admin: " + (note == null ? "paused by BUZZER" : note) : note)
                .param("a", admin).param("id", listingId).query(L.class).optional().orElseThrow(() -> BusinessException.notFound("Listing"));
        audit.record(admin, "ADMIN", "LISTING_" + decision, "LISTING", listingId, Map.of("note", note == null ? "" : note));
        notifyPartner(l.partnerId(), "Listing " + switch (decision) {
            case "ACTIVE" -> "approved";
            case "PAUSED" -> "paused";
            default -> "not approved";
        } + ": " + l.productName(), note == null ? "BUZZER reviewed your listing." : note);
    }

    // ----- Brands and RFQ invite rules ------------------------------------------------------------------

    public List<Map<String, Object>> brands() {
        return jdbc.sql("""
                SELECT b.id, b.name, b.active, (SELECT count(*) FROM products p WHERE p.brand_id = b.id) AS products FROM brands b ORDER BY b.name""")
                .query().listOfRows();
    }

    public List<Map<String, Object>> rules() {
        return jdbc.sql("""
                SELECT r.id, r.invite_count, r.created_at, c.id AS category_id, c.name AS category_name, ci.id AS city_id, ci.name AS city_name,
                       l.id AS locality_id, l.name AS locality_name
                FROM rfq_invite_rules r LEFT JOIN categories c ON c.id = r.category_id LEFT JOIN cities ci ON ci.id = r.city_id
                LEFT JOIN localities l ON l.id = r.locality_id WHERE r.active ORDER BY c.name NULLS FIRST, ci.name NULLS FIRST, l.name NULLS FIRST""")
                .query().listOfRows();
    }

    /** Creates or replaces the rule for a scope (category / city / locality, any may be empty). */
    @Transactional
    public List<Map<String, Object>> saveRule(RuleInput in, UUID admin) {
        if (in == null || in.inviteCount() == null || in.inviteCount() < 1 || in.inviteCount() > 20) throw BusinessException.invalid("Invite count must be 1-20");
        if (in.categoryId() == null && in.cityId() == null && in.localityId() == null) {
            throw BusinessException.invalid("Pick a category, city or locality - the default is the 'rfq.invite_count' setting");
        }
        jdbc.sql("""
                UPDATE rfq_invite_rules SET active = FALSE WHERE active
                  AND category_id IS NOT DISTINCT FROM CAST(:c AS uuid) AND city_id IS NOT DISTINCT FROM CAST(:ci AS uuid)
                  AND locality_id IS NOT DISTINCT FROM CAST(:l AS uuid)""")
                .param("c", in.categoryId()).param("ci", in.cityId()).param("l", in.localityId()).update();
        UUID id = jdbc.sql("""
                INSERT INTO rfq_invite_rules(category_id, city_id, locality_id, invite_count, created_by) VALUES (:c, :ci, :l, :n, :a) RETURNING id""")
                .param("c", in.categoryId()).param("ci", in.cityId()).param("l", in.localityId()).param("n", in.inviteCount()).param("a", admin)
                .query(UUID.class).single();
        audit.record(admin, "ADMIN", "RFQ_INVITE_RULE_SAVED", "RFQ_RULE", id, Map.of("invite_count", in.inviteCount()));
        return rules();
    }

    @Transactional
    public List<Map<String, Object>> deleteRule(UUID id, UUID admin) {
        int n = jdbc.sql("UPDATE rfq_invite_rules SET active = FALSE WHERE id = :id AND active").param("id", id).update();
        if (n == 0) throw BusinessException.notFound("Rule");
        audit.record(admin, "ADMIN", "RFQ_INVITE_RULE_REMOVED", "RFQ_RULE", id, Map.of());
        return rules();
    }

    private void notifyPartner(UUID partnerId, String title, String body) {
        UUID u = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
        notifications.notifyUser(u, title, body, "PARTNER_ACCOUNT", "PARTNER", partnerId);
    }
}
