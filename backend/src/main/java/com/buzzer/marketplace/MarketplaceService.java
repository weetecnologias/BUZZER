package com.buzzer.marketplace;

import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.buzzer.marketplace.MarketplaceSql.LISTING_JOINS;
import static com.buzzer.marketplace.MarketplaceSql.NEAREST_LOCATION;
import static com.buzzer.marketplace.MarketplaceSql.VISIBLE_LISTING;
import static com.buzzer.marketplace.MarketplaceSql.VISIBLE_SHOP;

/**
 * Shop & Buy: local shop discovery by distance, product search (full-text with stemming + fuzzy matching), product pages
 * with every shop's offer for price comparison, and shop profiles. Read-only; prices and stock are what shops declare.
 */
@Service
public class MarketplaceService {
    /** Customer position for distance sorting; both null = no location (city-wide results, no distance shown). */
    public record GeoPoint(BigDecimal lat, BigDecimal lng) {
        public static final GeoPoint NONE = new GeoPoint(null, null);

        public boolean known() {
            return lat != null && lng != null;
        }
    }

    /** Full-text (English stemming) or fuzzy (typo-tolerant) match on product name / description / brand. Aliases pr, b. */
    private static final String MATCH = """
            (CAST(:q AS text) IS NULL
              OR to_tsvector('english', pr.name || ' ' || coalesce(pr.description, '')) @@ plainto_tsquery('english', CAST(:q AS text))
              OR word_similarity(lower(CAST(:q AS text)), lower(pr.name)) >= 0.5
              OR lower(coalesce(b.name, '')) % lower(CAST(:q AS text))
              OR pr.name ILIKE '%' || CAST(:q AS text) || '%')""";

    private static final String RANK = """
            CASE WHEN CAST(:q AS text) IS NULL THEN 0 ELSE
              ts_rank(to_tsvector('english', pr.name || ' ' || coalesce(pr.description, '')), plainto_tsquery('english', CAST(:q AS text)))
              + word_similarity(lower(CAST(:q AS text)), lower(pr.name)) END""";

    private static final String IN_RADIUS = "(CAST(:lat AS numeric) IS NULL OR loc.distance_km IS NULL OR loc.distance_km <= :radius)";

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final Json json;

    public MarketplaceService(JdbcClient jdbc, SettingsService settings, Json json) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.json = json;
    }

    /** Position from explicit coordinates, else the centre of a locality. */
    public GeoPoint resolve(BigDecimal lat, BigDecimal lng, UUID localityId) {
        if (lat != null && lng != null) {
            if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lng.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
                throw BusinessException.invalid("Invalid coordinates");
            }
            return new GeoPoint(lat, lng);
        }
        if (localityId != null) {
            return jdbc.sql("SELECT latitude AS lat, longitude AS lng FROM localities WHERE id = :l").param("l", localityId)
                    .query(GeoPoint.class).optional().orElse(GeoPoint.NONE);
        }
        return GeoPoint.NONE;
    }

    /** Position of one of the customer's saved addresses (its own pin, else its locality centre). */
    public GeoPoint forAddress(UUID addressId, UUID customerUserId) {
        if (addressId == null) return GeoPoint.NONE;
        return jdbc.sql("""
                SELECT COALESCE(a.latitude, l.latitude) AS lat, COALESCE(a.longitude, l.longitude) AS lng
                FROM addresses a LEFT JOIN localities l ON l.id = a.locality_id
                WHERE a.id = :a AND a.user_id = :u AND a.deleted_at IS NULL""").param("a", addressId).param("u", customerUserId)
                .query(GeoPoint.class).optional().orElse(GeoPoint.NONE);
    }

    private BigDecimal radius() {
        return BigDecimal.valueOf(Math.max(1, settings.getInt("marketplace.search_radius_km", 25)));
    }

    // ----- Shops ----------------------------------------------------------------------------------------

    public List<Map<String, Object>> shops(GeoPoint g, UUID categoryId, String q, int limit) {
        String query = blankToNull(q);
        return jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.rating_avg, p.rating_count, loc.*,
                       (SELECT string_agg(r.role, ',' ORDER BY r.role) FROM partner_roles r
                        WHERE r.partner_id = p.id AND r.status = 'APPROVED' AND r.role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR')) AS roles,
                       (SELECT string_agg(DISTINCT c2.name, ', ') FROM partner_capabilities pc2 JOIN subcategories s2 ON s2.id = pc2.subcategory_id
                          JOIN categories c2 ON c2.id = s2.category_id
                        WHERE pc2.partner_id = p.id AND pc2.status = 'APPROVED' AND c2.vertical = 'MARKETPLACE' AND c2.active) AS categories,
                       (SELECT count(*) FROM vendor_listings l2 WHERE l2.partner_id = p.id AND l2.status = 'ACTIVE') AS product_count
                FROM partners p
                """ + NEAREST_LOCATION + """

                WHERE """ + " " + VISIBLE_SHOP + " AND " + IN_RADIUS + """

                  AND (CAST(:cat AS uuid) IS NULL OR EXISTS (
                        SELECT 1 FROM partner_capabilities pc JOIN subcategories s ON s.id = pc.subcategory_id
                        WHERE pc.partner_id = p.id AND pc.status = 'APPROVED' AND s.category_id = CAST(:cat AS uuid)))
                  AND (CAST(:q AS text) IS NULL OR p.display_name ILIKE '%' || CAST(:q AS text) || '%'
                       OR p.business_name ILIKE '%' || CAST(:q AS text) || '%')
                  AND EXISTS (SELECT 1 FROM partner_capabilities pc3 JOIN subcategories s3 ON s3.id = pc3.subcategory_id
                              JOIN categories c3 ON c3.id = s3.category_id
                              WHERE pc3.partner_id = p.id AND pc3.status = 'APPROVED' AND c3.active
                                AND c3.vertical IN (SELECT code FROM platform_verticals WHERE enabled AND code = 'MARKETPLACE'))
                ORDER BY loc.distance_km NULLS LAST, p.rating_avg DESC, p.display_name
                LIMIT :limit""")
                .param("lat", g.lat()).param("lng", g.lng()).param("radius", radius()).param("cat", categoryId).param("q", query)
                .param("limit", Math.min(Math.max(limit, 1), 100)).query().listOfRows();
    }

    public Map<String, Object> shop(UUID partnerId, GeoPoint g) {
        Map<String, Object> shop = jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.rating_avg, p.rating_count, p.contact_phone, p.created_at, loc.*,
                       (SELECT string_agg(r.role, ',' ORDER BY r.role) FROM partner_roles r
                        WHERE r.partner_id = p.id AND r.status = 'APPROVED' AND r.role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR')) AS roles
                FROM partners p
                """ + NEAREST_LOCATION + """

                WHERE p.id = :p AND""" + " " + VISIBLE_SHOP)
                .param("lat", g.lat()).param("lng", g.lng()).param("p", partnerId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Shop"));
        Map<String, Object> out = new LinkedHashMap<>(shop);
        out.put("locations", jdbc.sql("""
                SELECT pl.id, pl.name, pl.address, l.name AS locality_name, pl.opening_hours, pl.pickup_enabled, pl.delivery_enabled,
                       pl.delivery_fee, pl.free_delivery_above, pl.min_order_value, pl.service_radius_km, pl.phone, pl.is_primary
                FROM partner_locations pl LEFT JOIN localities l ON l.id = pl.locality_id
                WHERE pl.partner_id = :p AND pl.active ORDER BY pl.is_primary DESC, pl.name""").param("p", partnerId).query().listOfRows());
        out.put("categories", jdbc.sql("""
                SELECT DISTINCT c.id, c.name, c.icon, c.sort_order FROM partner_capabilities pc JOIN subcategories s ON s.id = pc.subcategory_id
                JOIN categories c ON c.id = s.category_id
                WHERE pc.partner_id = :p AND pc.status = 'APPROVED' AND c.vertical = 'MARKETPLACE' AND c.active ORDER BY c.sort_order""")
                .param("p", partnerId).query().listOfRows());
        return out;
    }

    // ----- Products ---------------------------------------------------------------------------------------

    /** Products with at least one visible offer, best match first. */
    public Map<String, Object> products(String q, UUID categoryId, UUID subcategoryId, UUID partnerId, GeoPoint g, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 50);
        int offset = Math.max(page, 0) * pageSize;
        String query = blankToNull(q);
        if (query != null && query.length() > 100) throw BusinessException.invalid("Search text is too long");
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT pr.id, pr.code, pr.name, pr.unit, pr.image_url, pr.specs::text AS specs, b.name AS brand,
                       s.id AS subcategory_id, s.name AS subcategory_name, c.id AS category_id, c.name AS category_name, c.icon AS category_icon,
                       count(DISTINCT l.partner_id) AS shop_count, min(l.price) AS min_price, max(l.price) AS max_price,
                       min(loc.distance_km) AS nearest_km, bool_or(l.stock_status <> 'OUT_OF_STOCK') AS in_stock,
                       max(""" + RANK + """
                ) AS rank
                """ + LISTING_JOINS + "\n" + NEAREST_LOCATION + """

                WHERE""" + " " + VISIBLE_LISTING + " AND " + IN_RADIUS + " AND " + MATCH + """

                  AND (CAST(:cat AS uuid) IS NULL OR c.id = CAST(:cat AS uuid))
                  AND (CAST(:sub AS uuid) IS NULL OR s.id = CAST(:sub AS uuid))
                  AND (CAST(:pid AS uuid) IS NULL OR p.id = CAST(:pid AS uuid))
                GROUP BY pr.id, b.name, s.id, c.id
                ORDER BY rank DESC, shop_count DESC, min_price, pr.sort_order, pr.name
                LIMIT :lim OFFSET :off""")
                .param("lat", g.lat()).param("lng", g.lng()).param("radius", radius()).param("q", query).param("cat", categoryId)
                .param("sub", subcategoryId).param("pid", partnerId).param("lim", pageSize + 1).param("off", offset).query().listOfRows();
        boolean more = rows.size() > pageSize;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(rows.size(), pageSize))) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.remove("rank");
            m.put("specs", json.map((String) r.get("specs")));
            items.add(m);
        }
        return Map.of("items", items, "page", Math.max(page, 0), "size", pageSize, "has_more", more);
    }

    /** Product page: specifications, variants and every visible shop offer (cheapest and nearest flagged). */
    public Map<String, Object> product(UUID productId, GeoPoint g) {
        Map<String, Object> p = jdbc.sql("""
                SELECT pr.id, pr.code, pr.name, pr.description, pr.unit, pr.image_url, pr.specs::text AS specs, pr.tax_rate_percent,
                       b.name AS brand, s.id AS subcategory_id, s.name AS subcategory_name, c.id AS category_id, c.name AS category_name,
                       c.advisory_notice
                FROM products pr JOIN subcategories s ON s.id = pr.subcategory_id JOIN categories c ON c.id = s.category_id
                LEFT JOIN brands b ON b.id = pr.brand_id
                WHERE pr.id = :id AND pr.status = 'ACTIVE' AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)""")
                .param("id", productId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("Product"));
        Map<String, Object> out = new LinkedHashMap<>(p);
        out.put("specs", json.map((String) p.get("specs")));
        out.put("variants", jdbc.sql("SELECT id, name, sku, attributes::text AS attributes FROM product_variants WHERE product_id = :id AND active ORDER BY sort_order, name")
                .param("id", productId).query().listOfRows().stream().map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>(v);
                    m.put("attributes", json.map((String) v.get("attributes")));
                    return m;
                }).toList());
        List<Map<String, Object>> offers = jdbc.sql("""
                SELECT l.id AS listing_id, l.variant_id, v.name AS variant_name, l.price, l.mrp, l.stock_status, l.stock_updated_at, l.moq,
                       l.max_order_qty, l.bulk_price, l.bulk_min_qty, l.lead_time_days, l.notes,
                       p.id AS partner_id, p.display_name AS shop_name, p.rating_avg, p.rating_count, loc.*,
                       (SELECT string_agg(r.role, ',' ORDER BY r.role) FROM partner_roles r
                        WHERE r.partner_id = p.id AND r.status = 'APPROVED' AND r.role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR')) AS roles
                """ + LISTING_JOINS + "\n" + NEAREST_LOCATION + """

                WHERE v.product_id = :id AND""" + " " + VISIBLE_LISTING + " AND " + IN_RADIUS + """

                ORDER BY (l.stock_status = 'OUT_OF_STOCK'), l.price, loc.distance_km NULLS LAST""")
                .param("lat", g.lat()).param("lng", g.lng()).param("radius", radius()).param("id", productId).query().listOfRows();
        BigDecimal cheapest = offers.stream().filter(o -> !"OUT_OF_STOCK".equals(o.get("stock_status")))
                .map(o -> (BigDecimal) o.get("price")).min(BigDecimal::compareTo).orElse(null);
        BigDecimal nearest = offers.stream().map(o -> (BigDecimal) o.get("distance_km")).filter(java.util.Objects::nonNull)
                .min(BigDecimal::compareTo).orElse(null);
        List<Map<String, Object>> marked = new ArrayList<>();
        for (Map<String, Object> o : offers) {
            Map<String, Object> m = new LinkedHashMap<>(o);
            m.put("cheapest", cheapest != null && !"OUT_OF_STOCK".equals(o.get("stock_status")) && cheapest.compareTo((BigDecimal) o.get("price")) == 0);
            m.put("nearest", nearest != null && o.get("distance_km") != null && nearest.compareTo((BigDecimal) o.get("distance_km")) == 0);
            marked.add(m);
        }
        out.put("offers", marked);
        out.put("stock_note", "Stock is as declared by each shop (see 'updated'). The shop confirms availability when it accepts your order.");
        return out;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
