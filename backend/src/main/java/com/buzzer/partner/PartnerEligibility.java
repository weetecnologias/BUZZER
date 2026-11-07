package com.buzzer.partner;

import com.buzzer.request.RequestRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A partner is eligible for a request only when ALL hold:
 *  - registration APPROVED and KYC VERIFIED (separate states; subscriptions never bypass these)
 *  - currently AVAILABLE
 *  - operates in the request's city, and (if it declares service areas) covers the request locality
 *  - holds an APPROVED capability for the request's subcategory (whole subcategory, or the exact service item).
 *    Category-level requests (no subcategory) require an approved capability somewhere in that category.
 */
@Component
public class PartnerEligibility {
    private static final String ELIGIBLE_SQL = """
            SELECT p.id, p.display_name, p.business_name, p.partner_type, p.rating_avg, p.rating_count,
                   (SELECT count(*) FROM assignments a WHERE a.partner_id = p.id AND a.status IN ('ACTIVE','CONFIRMED','IN_PROGRESS')) AS active_jobs,
                   (SELECT string_agg(DISTINCT l.name, ', ') FROM partner_service_areas sa JOIN localities l ON l.id = sa.locality_id
                      WHERE sa.partner_id = p.id) AS service_areas,
                   EXISTS (SELECT 1 FROM partner_invitations pi WHERE pi.partner_id = p.id AND pi.request_id = :req) AS already_invited
            FROM partners p
            WHERE p.registration_status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND p.availability = 'AVAILABLE'
              AND p.city_id = :city
              AND (CAST(:loc AS uuid) IS NULL OR NOT EXISTS (SELECT 1 FROM partner_service_areas sa WHERE sa.partner_id = p.id)
                   OR EXISTS (SELECT 1 FROM partner_service_areas sa WHERE sa.partner_id = p.id AND sa.locality_id = CAST(:loc AS uuid)))
              AND EXISTS (
                SELECT 1 FROM partner_capabilities pc JOIN subcategories s ON s.id = pc.subcategory_id
                WHERE pc.partner_id = p.id AND pc.status = 'APPROVED'
                  AND (CASE WHEN CAST(:sub AS uuid) IS NULL THEN s.category_id = :cat
                            ELSE pc.subcategory_id = CAST(:sub AS uuid)
                                 AND (pc.service_item_id IS NULL OR CAST(:svc AS uuid) IS NULL OR pc.service_item_id = CAST(:svc AS uuid)) END))
            """;

    private final JdbcClient jdbc;

    public PartnerEligibility(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> eligibleFor(RequestRepository.Row r) {
        return jdbc.sql(ELIGIBLE_SQL + " ORDER BY p.rating_avg DESC, active_jobs ASC, p.created_at")
                .param("req", r.id()).param("city", r.cityId()).param("loc", r.localityId()).param("cat", r.categoryId())
                .param("sub", r.subcategoryId()).param("svc", r.serviceItemId()).query().listOfRows();
    }

    public boolean isEligible(RequestRepository.Row r, UUID partnerId) {
        return jdbc.sql("SELECT count(*) FROM (" + ELIGIBLE_SQL + " AND p.id = :pid) x")
                .param("req", r.id()).param("city", r.cityId()).param("loc", r.localityId()).param("cat", r.categoryId())
                .param("sub", r.subcategoryId()).param("svc", r.serviceItemId()).param("pid", partnerId)
                .query(Integer.class).single() > 0;
    }
}
