package com.buzzer.quotation;

import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import com.buzzer.marketplace.MarketplaceSql;
import com.buzzer.request.RequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which shops a quotation request (PRODUCT_RFQ) goes to. On top of the base eligibility (approved, KYC verified, available,
 * city / service area, approved category capability) a vendor must hold an approved shop-type role and - when both sides
 * have coordinates - the request must be inside the shop's service radius. Ranking: wholesalers / distributors first for
 * bulk requests, then nearest, then shops that list products in that subcategory, then rating and workload.
 * How many are invited comes from rfq_invite_rules (category / city / locality), else the rfq.invite_count setting.
 */
@Component
public class RfqVendorSelector {
    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final Json json;

    public RfqVendorSelector(JdbcClient jdbc, SettingsService settings, Json json) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.json = json;
    }

    public int inviteCount(RequestRepository.Row r) {
        int fallback = Math.min(Math.max(settings.getInt("rfq.invite_count", 7), 1), 20);
        return jdbc.sql("""
                SELECT invite_count FROM rfq_invite_rules WHERE active
                  AND (category_id IS NULL OR category_id = :cat) AND (city_id IS NULL OR city_id = :city)
                  AND (locality_id IS NULL OR locality_id = CAST(:loc AS uuid))
                ORDER BY (category_id IS NOT NULL) DESC, (locality_id IS NOT NULL) DESC, (city_id IS NOT NULL) DESC LIMIT 1""")
                .param("cat", r.categoryId()).param("city", r.cityId()).param("loc", r.localityId()).query(Integer.class).optional().orElse(fallback);
    }

    public boolean isBulk(UUID requestId) {
        String answers = jdbc.sql("SELECT answers::text FROM service_requests WHERE id = :r").param("r", requestId).query(String.class).optional().orElse(null);
        Object rfq = json.map(answers).get("rfq");
        return rfq instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("bulk"));
    }

    /** Orders the base-eligible partners for a quotation request and drops those that are not shops or are out of range. */
    public List<Map<String, Object>> rank(RequestRepository.Row r, List<Map<String, Object>> eligible, boolean bulk) {
        if (eligible.isEmpty()) return List.of();
        record Pt(BigDecimal lat, BigDecimal lng) {}
        Pt pt = jdbc.sql("""
                SELECT COALESCE(CAST(r.address_snapshot->>'latitude' AS numeric), l.latitude) AS lat,
                       COALESCE(CAST(r.address_snapshot->>'longitude' AS numeric), l.longitude) AS lng
                FROM service_requests r LEFT JOIN localities l ON l.id = r.locality_id WHERE r.id = :r""").param("r", r.id())
                .query(Pt.class).single();
        Map<Object, Integer> baseOrder = new HashMap<>();
        for (int i = 0; i < eligible.size(); i++) baseOrder.put(eligible.get(i).get("id"), i);
        List<UUID> ids = eligible.stream().map(m -> (UUID) m.get("id")).toList();
        record V(UUID id, String displayName, BigDecimal distanceKm, BigDecimal serviceRadiusKm, boolean wholesale, int listings) {}
        List<V> vendors = new ArrayList<>(jdbc.sql("""
                SELECT p.id, p.display_name, loc.distance_km, loc.service_radius_km,
                       EXISTS (SELECT 1 FROM partner_roles pr WHERE pr.partner_id = p.id AND pr.status = 'APPROVED' AND pr.role IN ('WHOLESALER','DISTRIBUTOR')) AS wholesale,
                       (SELECT count(*) FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id
                        WHERE l.partner_id = p.id AND l.status = 'ACTIVE'
                          AND (pr.subcategory_id = CAST(:sub AS uuid) OR (CAST(:sub AS uuid) IS NULL AND pr.subcategory_id IN
                               (SELECT id FROM subcategories WHERE category_id = :cat)))) AS listings
                FROM partners p
                """ + MarketplaceSql.NEAREST_LOCATION + """

                WHERE p.id IN (:ids)
                  AND EXISTS (SELECT 1 FROM partner_roles pr WHERE pr.partner_id = p.id AND pr.status = 'APPROVED' AND pr.role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR'))
                  AND (loc.distance_km IS NULL OR loc.distance_km <= loc.service_radius_km)""")
                .param("lat", pt.lat()).param("lng", pt.lng()).param("ids", ids).param("sub", r.subcategoryId()).param("cat", r.categoryId())
                .query(V.class).list());
        boolean preferWholesale = bulk && settings.getBoolean("rfq.bulk_prefers_wholesalers", true);
        vendors.sort(Comparator.comparing((V v) -> preferWholesale && v.wholesale() ? 0 : 1)
                .thenComparing(v -> v.distanceKm() == null ? BigDecimal.valueOf(100000) : v.distanceKm())
                .thenComparing(v -> -v.listings())
                .thenComparing(v -> baseOrder.getOrDefault(v.id(), Integer.MAX_VALUE)));
        Map<UUID, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> m : eligible) byId.put((UUID) m.get("id"), m);
        List<Map<String, Object>> out = new ArrayList<>();
        for (V v : vendors) {
            Map<String, Object> m = new HashMap<>(byId.get(v.id()));
            m.put("distance_km", v.distanceKm());
            m.put("wholesale", v.wholesale());
            out.add(m);
        }
        return out;
    }
}
