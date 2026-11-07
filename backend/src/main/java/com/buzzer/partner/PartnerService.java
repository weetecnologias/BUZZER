package com.buzzer.partner;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.identity.Role;
import com.buzzer.identity.UserRepository;
import com.buzzer.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Partner onboarding. Registration, KYC verification, category approval and availability are separate
 * states, and each capability (subcategory / service) must be individually approved by an admin.
 */
@Service
public class PartnerService {
    public record Registration(String partnerType, String displayName, String businessName, String email, UUID cityId, String address,
                               List<UUID> subcategoryIds, List<UUID> serviceItemIds, List<UUID> localityIds, UUID termsVersionId) {}

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;

    public PartnerService(JdbcClient jdbc, UserRepository users, AuditService audit, NotificationService notifications) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.notifications = notifications;
    }

    public UUID partnerIdFor(UUID userId) {
        return jdbc.sql("SELECT id FROM partners WHERE user_id = :u").param("u", userId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.forbidden("Complete partner registration first"));
    }

    @Transactional
    public UUID register(UUID userId, Registration in, String ip, String userAgent) {
        if (!List.of("INDIVIDUAL", "BUSINESS").contains(in.partnerType())) throw BusinessException.invalid("Partner type must be INDIVIDUAL or BUSINESS");
        if (in.displayName() == null || in.displayName().trim().length() < 2) throw BusinessException.invalid("Name is required");
        if ("BUSINESS".equals(in.partnerType()) && (in.businessName() == null || in.businessName().isBlank())) {
            throw BusinessException.invalid("Registered business name is required");
        }
        if (in.cityId() == null) throw BusinessException.invalid("City is required");
        if (in.subcategoryIds() == null || in.subcategoryIds().isEmpty()) throw BusinessException.invalid("Select at least one service category");
        if (in.termsVersionId() == null) throw BusinessException.invalid("You must accept the partner terms");
        Integer exists = jdbc.sql("SELECT count(*) FROM partners WHERE user_id = :u").param("u", userId).query(Integer.class).single();
        if (exists > 0) throw new BusinessException(ErrorCode.DUPLICATE, "You are already registered as a partner");
        Integer termsOk = jdbc.sql("SELECT count(*) FROM terms_versions WHERE id = :t AND audience = 'PARTNER' AND active")
                .param("t", in.termsVersionId()).query(Integer.class).single();
        if (termsOk == 0) throw BusinessException.invalid("Terms version not found or no longer active");

        var user = users.findById(userId).orElseThrow();
        UUID partnerId = jdbc.sql("""
                INSERT INTO partners(user_id, partner_type, display_name, business_name, contact_phone, email, city_id, address)
                VALUES (:u, :t, :n, :b, :ph, :e, :c, :a) RETURNING id""")
                .param("u", userId).param("t", in.partnerType()).param("n", in.displayName().trim()).param("b", in.businessName())
                .param("ph", user.phone()).param("e", in.email()).param("c", in.cityId()).param("a", in.address())
                .query(UUID.class).single();
        for (UUID sub : in.subcategoryIds().stream().distinct().toList()) requestCapability(partnerId, sub, null);
        if (in.serviceItemIds() != null) {
            for (UUID svc : in.serviceItemIds().stream().distinct().toList()) {
                UUID sub = jdbc.sql("SELECT subcategory_id FROM service_items WHERE id = :s").param("s", svc).query(UUID.class).optional()
                        .orElseThrow(() -> BusinessException.invalid("Unknown service"));
                requestCapability(partnerId, sub, svc);
            }
        }
        if (in.localityIds() != null) {
            for (UUID loc : in.localityIds().stream().distinct().toList()) {
                int n = jdbc.sql("""
                        INSERT INTO partner_service_areas(partner_id, locality_id)
                        SELECT :p, id FROM localities WHERE id = :l AND city_id = :c ON CONFLICT DO NOTHING""")
                        .param("p", partnerId).param("l", loc).param("c", in.cityId()).update();
                if (n == 0) throw BusinessException.invalid("Service area must be in the selected city");
            }
        }
        jdbc.sql("""
                INSERT INTO terms_acceptances(user_id, terms_version_id, ip_address, user_agent) VALUES (:u, :t, :ip, :ua)
                ON CONFLICT DO NOTHING""").param("u", userId).param("t", in.termsVersionId()).param("ip", ip)
                .param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length()))).update();
        users.grantRole(userId, Role.PARTNER, null);
        audit.record(userId, "PARTNER", "PARTNER_REGISTERED", "PARTNER", partnerId, Map.of("type", in.partnerType()));
        return partnerId;
    }

    /** Partner asks for an additional capability. Always requires admin approval. */
    @Transactional
    public UUID requestCapability(UUID partnerId, UUID subcategoryId, UUID serviceItemId) {
        Integer ok = jdbc.sql("SELECT count(*) FROM subcategories WHERE id = :s AND active").param("s", subcategoryId).query(Integer.class).single();
        if (ok == 0) throw BusinessException.invalid("Unknown subcategory");
        return jdbc.sql("""
                INSERT INTO partner_capabilities(partner_id, subcategory_id, service_item_id) VALUES (:p, :s, :i)
                ON CONFLICT (partner_id, subcategory_id, COALESCE(service_item_id, '00000000-0000-0000-0000-000000000000'::uuid))
                DO UPDATE SET status = CASE WHEN partner_capabilities.status IN ('REJECTED','REVOKED') THEN 'REQUESTED' ELSE partner_capabilities.status END
                RETURNING id""").param("p", partnerId).param("s", subcategoryId).param("i", serviceItemId).query(UUID.class).single();
    }

    public void submitKyc(UUID partnerId, String docType, String docNumber) {
        if (docType == null || !List.of("AADHAAR", "PAN", "GSTIN", "DRIVING_LICENCE", "TRADE_LICENCE", "QUALIFICATION", "OTHER").contains(docType)) {
            throw BusinessException.invalid("Unsupported document type");
        }
        if (docNumber == null || docNumber.trim().length() < 4) throw BusinessException.invalid("Document number is required");
        String clean = docNumber.replaceAll("\\s", "");
        // Only a masked number is stored here; the document file goes to private object storage (pending integration).
        String masked = "*".repeat(Math.max(0, clean.length() - 4)) + clean.substring(clean.length() - 4);
        jdbc.sql("INSERT INTO partner_kyc_documents(partner_id, doc_type, doc_number_masked) VALUES (:p, :t, :m)")
                .param("p", partnerId).param("t", docType).param("m", masked).update();
        jdbc.sql("UPDATE partners SET kyc_status = 'SUBMITTED', updated_at = now() WHERE id = :p AND kyc_status IN ('PENDING','REJECTED','EXPIRED')")
                .param("p", partnerId).update();
    }

    public Map<String, Object> profile(UUID partnerId) {
        Map<String, Object> p = jdbc.sql("""
                SELECT p.id, p.partner_type, p.display_name, p.business_name, p.contact_phone, p.email, p.city_id, c.name AS city_name,
                       p.address, p.registration_status, p.kyc_status, p.availability, p.rating_avg, p.rating_count, p.review_note, p.created_at
                FROM partners p JOIN cities c ON c.id = p.city_id WHERE p.id = :p""").param("p", partnerId).query().singleRow();
        return new java.util.LinkedHashMap<>(Map.of(
                "partner", p,
                "capabilities", capabilities(partnerId),
                "service_areas", jdbc.sql("""
                        SELECT l.id, l.name, l.pincode FROM partner_service_areas sa JOIN localities l ON l.id = sa.locality_id
                        WHERE sa.partner_id = :p ORDER BY l.name""").param("p", partnerId).query().listOfRows(),
                "kyc_documents", jdbc.sql("""
                        SELECT id, doc_type, doc_number_masked, status, created_at FROM partner_kyc_documents WHERE partner_id = :p ORDER BY created_at DESC""")
                        .param("p", partnerId).query().listOfRows()));
    }

    public List<Map<String, Object>> capabilities(UUID partnerId) {
        return jdbc.sql("""
                SELECT pc.id, pc.status, pc.review_note, pc.created_at, s.id AS subcategory_id, s.name AS subcategory_name,
                       c.name AS category_name, i.id AS service_item_id, i.name AS service_name
                FROM partner_capabilities pc JOIN subcategories s ON s.id = pc.subcategory_id JOIN categories c ON c.id = s.category_id
                LEFT JOIN service_items i ON i.id = pc.service_item_id WHERE pc.partner_id = :p ORDER BY c.sort_order, s.sort_order""")
                .param("p", partnerId).query().listOfRows();
    }

    public void setAvailability(UUID partnerId, String availability) {
        if (!List.of("AVAILABLE", "UNAVAILABLE").contains(availability)) throw BusinessException.invalid("Invalid availability");
        jdbc.sql("UPDATE partners SET availability = :a, updated_at = now() WHERE id = :p").param("a", availability).param("p", partnerId).update();
    }

    /** Leads visible to THIS partner only. Customer contact details are withheld until assignment. */
    public List<Map<String, Object>> leads(UUID partnerId, String status) {
        return jdbc.sql("""
                SELECT i.id AS invitation_id, i.invitation_number, i.status, i.created_at, r.id AS request_id, r.request_number,
                       r.description, r.preferred_start, r.priority, c.name AS category_name, s.name AS subcategory_name, i2.name AS service_name,
                       l.name AS locality_name, r.answers::text AS answers, r.line_items::text AS line_items, r.estimated_total, r.booking_mode,
                       (SELECT q.id FROM quotations q WHERE q.invitation_id = i.id ORDER BY q.created_at DESC LIMIT 1) AS quotation_id,
                       (SELECT q.total_amount FROM quotations q WHERE q.invitation_id = i.id ORDER BY q.created_at DESC LIMIT 1) AS quoted_amount
                FROM partner_invitations i JOIN service_requests r ON r.id = i.request_id JOIN categories c ON c.id = r.category_id
                LEFT JOIN subcategories s ON s.id = r.subcategory_id LEFT JOIN service_items i2 ON i2.id = r.service_item_id
                LEFT JOIN localities l ON l.id = r.locality_id
                WHERE i.partner_id = :p AND (CAST(:s AS text) IS NULL OR i.status = CAST(:s AS text))
                ORDER BY i.created_at DESC LIMIT 200""").param("p", partnerId).param("s", status).query().listOfRows();
    }

    public List<Map<String, Object>> assignments(UUID partnerId, boolean activeOnly) {
        return jdbc.sql("""
                SELECT a.id, a.assignment_number, a.status, a.created_at, a.price_snapshot::text AS price_snapshot, r.id AS request_id,
                       r.request_number, r.status AS request_status, c.name AS category_name, i.name AS service_name,
                       b.id AS booking_id, b.booking_number, b.status AS booking_status, b.scheduled_at,
                       CASE WHEN a.status IN ('CONFIRMED','IN_PROGRESS') THEN u.full_name END AS customer_name,
                       CASE WHEN a.status IN ('CONFIRMED','IN_PROGRESS') THEN u.phone END AS customer_phone,
                       CASE WHEN a.status IN ('CONFIRMED','IN_PROGRESS') THEN r.address_snapshot::text END AS address
                FROM assignments a JOIN service_requests r ON r.id = a.request_id JOIN categories c ON c.id = r.category_id
                LEFT JOIN service_items i ON i.id = r.service_item_id LEFT JOIN bookings b ON b.assignment_id = a.id
                JOIN users u ON u.id = r.customer_user_id
                WHERE a.partner_id = :p AND (NOT :active OR a.status IN ('ACTIVE','CONFIRMED','IN_PROGRESS'))
                ORDER BY a.created_at DESC LIMIT 200""").param("p", partnerId).param("active", activeOnly).query().listOfRows();
    }

    public List<Map<String, Object>> payouts(UUID partnerId) {
        return jdbc.sql("""
                SELECT po.id, po.payout_number, po.amount, po.status, po.due_on, po.paid_at, po.reference, r.request_number
                FROM partner_payouts po LEFT JOIN service_requests r ON r.id = po.request_id WHERE po.partner_id = :p ORDER BY po.created_at DESC""")
                .param("p", partnerId).query().listOfRows();
    }

    // ----- Admin review -----------------------------------------------------------------------------

    @Transactional
    public void reviewRegistration(UUID partnerId, String decision, String note, UUID adminUserId) {
        if (!List.of("APPROVED", "REJECTED", "SUSPENDED").contains(decision)) throw BusinessException.invalid("Invalid decision");
        int n = jdbc.sql("UPDATE partners SET registration_status = :d, review_note = :n, updated_at = now() WHERE id = :p")
                .param("d", decision).param("n", note).param("p", partnerId).update();
        if (n == 0) throw BusinessException.notFound("Partner");
        audit.record(adminUserId, "ADMIN", "PARTNER_REGISTRATION_" + decision, "PARTNER", partnerId, Map.of("note", note == null ? "" : note));
        notifyPartner(partnerId, "Registration " + decision.toLowerCase(), note == null ? "Your partner registration was reviewed." : note);
    }

    @Transactional
    public void reviewKyc(UUID partnerId, String decision, String note, UUID adminUserId) {
        if (!List.of("VERIFIED", "REJECTED").contains(decision)) throw BusinessException.invalid("Invalid decision");
        int n = jdbc.sql("UPDATE partners SET kyc_status = :d, review_note = COALESCE(:n, review_note), updated_at = now() WHERE id = :p")
                .param("d", decision).param("n", note).param("p", partnerId).update();
        if (n == 0) throw BusinessException.notFound("Partner");
        jdbc.sql("UPDATE partner_kyc_documents SET status = :s, reviewed_by = :a, reviewed_at = now() WHERE partner_id = :p AND status = 'SUBMITTED'")
                .param("s", decision).param("a", adminUserId).param("p", partnerId).update();
        audit.record(adminUserId, "ADMIN", "PARTNER_KYC_" + decision, "PARTNER", partnerId, Map.of());
        notifyPartner(partnerId, "KYC " + decision.toLowerCase(), note == null ? "Your KYC documents were reviewed." : note);
    }

    @Transactional
    public void reviewCapability(UUID capabilityId, String decision, String note, UUID adminUserId) {
        if (!List.of("APPROVED", "REJECTED", "REVOKED").contains(decision)) throw BusinessException.invalid("Invalid decision");
        UUID partnerId = jdbc.sql("""
                UPDATE partner_capabilities SET status = :d, review_note = :n, reviewed_by = :a, reviewed_at = now() WHERE id = :id RETURNING partner_id""")
                .param("d", decision).param("n", note).param("a", adminUserId).param("id", capabilityId).query(UUID.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Capability"));
        audit.record(adminUserId, "ADMIN", "PARTNER_CAPABILITY_" + decision, "PARTNER", partnerId, Map.of("capabilityId", capabilityId.toString()));
        notifyPartner(partnerId, "Service capability " + decision.toLowerCase(), note == null ? "A service capability was reviewed." : note);
    }

    private void notifyPartner(UUID partnerId, String title, String body) {
        UUID u = jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
        notifications.notifyUser(u, title, body, "PARTNER_ACCOUNT", "PARTNER", partnerId);
    }
}
