package com.buzzer.request;

import com.buzzer.agent.AgentAllocationService;
import com.buzzer.catalogue.CatalogueService;
import com.buzzer.catalogue.FormSchemaValidator;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.Json;
import com.buzzer.notification.NotificationService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class RequestService {
    public record CreateCommand(UUID customerUserId, UUID createdBy, String createdVia, UUID categoryId, UUID subcategoryId,
                                UUID serviceItemId, UUID addressId, Map<String, Object> answers, String description,
                                OffsetDateTime preferredStart, OffsetDateTime preferredEnd, String priority,
                                String idempotencyKey, String consentMethod,
                                String bookingMode, List<Map<String, Object>> lineItems, java.math.BigDecimal estimatedTotal,
                                boolean hasQuoteItems, boolean skipFormValidation) {
        /** Form-based request (customer or agent filled a dynamic form). */
        public CreateCommand(UUID customerUserId, UUID createdBy, String createdVia, UUID categoryId, UUID subcategoryId,
                             UUID serviceItemId, UUID addressId, Map<String, Object> answers, String description,
                             OffsetDateTime preferredStart, OffsetDateTime preferredEnd, String priority,
                             String idempotencyKey, String consentMethod) {
            this(customerUserId, createdBy, createdVia, categoryId, subcategoryId, serviceItemId, addressId, answers, description,
                    preferredStart, preferredEnd, priority, idempotencyKey, consentMethod, "AGENT_ASSISTED", null, null, false, false);
        }
    }

    public record Created(UUID id, String requestNumber, String status, boolean duplicate) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final BusinessIds ids;
    private final CatalogueService catalogue;
    private final FormSchemaValidator forms;
    private final AgentAllocationService allocation;
    private final RequestRepository requests;
    private final NotificationService notifications;
    private final AuditService audit;

    public RequestService(JdbcClient jdbc, Json json, BusinessIds ids, CatalogueService catalogue, FormSchemaValidator forms,
                          AgentAllocationService allocation, RequestRepository requests, NotificationService notifications,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ids = ids;
        this.catalogue = catalogue;
        this.forms = forms;
        this.allocation = allocation;
        this.requests = requests;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public Created create(CreateCommand cmd) {
        if (cmd.idempotencyKey() != null) {
            if (cmd.idempotencyKey().length() > 100) throw BusinessException.invalid("Idempotency key too long");
            Optional<Created> dup = jdbc.sql("""
                    SELECT id, request_number, status, TRUE AS duplicate FROM service_requests
                    WHERE customer_user_id = :c AND idempotency_key = :k""")
                    .param("c", cmd.customerUserId()).param("k", cmd.idempotencyKey()).query(Created.class).optional();
            if (dup.isPresent()) return dup.get();
        }
        Integer isCustomer = jdbc.sql("SELECT count(*) FROM user_roles WHERE user_id = :u AND role = 'CUSTOMER'")
                .param("u", cmd.customerUserId()).query(Integer.class).single();
        if (isCustomer == 0) throw BusinessException.notFound("Customer");

        CatalogueService.ServiceContext ctx = catalogue.resolve(cmd.categoryId(), cmd.subcategoryId(), cmd.serviceItemId());
        // Cart checkouts and free-text agent requests carry their own structure (line items / message) instead of a form.
        Map<String, Object> answers = cmd.skipFormValidation()
                ? (cmd.answers() == null ? Map.of() : cmd.answers())
                : forms.validateAnswers(ctx.formSchema(), cmd.answers());
        String bookingMode = cmd.bookingMode() == null ? "AGENT_ASSISTED" : cmd.bookingMode();
        if (!List.of("SELF", "AGENT_ASSISTED", "AGENT_DIRECT").contains(bookingMode)) throw BusinessException.invalid("Invalid booking mode");
        if (cmd.description() != null && cmd.description().length() > 4000) throw BusinessException.invalid("Description too long");
        if (cmd.preferredStart() != null && cmd.preferredEnd() != null && cmd.preferredEnd().isBefore(cmd.preferredStart())) {
            throw BusinessException.invalid("Preferred end time must be after the start time");
        }
        String priority = cmd.priority() == null ? "NORMAL" : cmd.priority();
        if (!List.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority)) throw BusinessException.invalid("Invalid priority");

        // Address must belong to the customer; we store a snapshot so later edits don't rewrite history.
        Map<String, Object> address = null;
        UUID cityId;
        UUID localityId = null;
        if (cmd.addressId() != null) {
            address = jdbc.sql("""
                    SELECT a.id, a.label, a.line1, a.line2, a.landmark, a.pincode, a.city_id, a.locality_id, a.latitude, a.longitude,
                           c.name AS city_name, l.name AS locality_name
                    FROM addresses a JOIN cities c ON c.id = a.city_id LEFT JOIN localities l ON l.id = a.locality_id
                    WHERE a.id = :a AND a.user_id = :u AND a.deleted_at IS NULL""")
                    .param("a", cmd.addressId()).param("u", cmd.customerUserId()).query().listOfRows().stream().findFirst()
                    .orElseThrow(() -> BusinessException.invalid("Address not found for this customer"));
            cityId = (UUID) address.get("city_id");
            localityId = (UUID) address.get("locality_id");
        } else {
            cityId = jdbc.sql("SELECT city_id FROM customer_profiles WHERE user_id = :u").param("u", cmd.customerUserId())
                    .query(UUID.class).optional().orElse(null);
            if (cityId == null) throw BusinessException.invalid("Please add an address");
        }
        Integer available = jdbc.sql("SELECT count(*) FROM category_cities WHERE category_id = :c AND city_id = :city AND active")
                .param("c", ctx.categoryId()).param("city", cityId).query(Integer.class).single();
        if (available == 0) throw BusinessException.invalid("This service is not yet available in your city");

        UUID agentId = allocation.activeLink(cmd.customerUserId(), "PRIMARY")
                .or(() -> allocation.allocatePrimary(cmd.customerUserId(), null, "AUTO_ON_FIRST_REQUEST")).orElse(null);

        boolean byAgent = "AGENT_APP".equals(cmd.createdVia());
        String status = byAgent ? RequestStatus.AGENT_REVIEW.name() : RequestStatus.REQUEST_CREATED.name();
        int slaMinutes = jdbc.sql("SELECT first_response_minutes FROM sla_rules WHERE priority = :p AND active")
                .param("p", priority).query(Integer.class).optional().orElse(120);
        String number = ids.next(BusinessIds.Kind.REQUEST);
        UUID id = jdbc.sql("""
                INSERT INTO service_requests(request_number, customer_user_id, created_by_user_id, created_via, consent_method,
                  consent_at, customer_confirmed_at, category_id, subcategory_id, service_item_id, city_id, locality_id, address_id,
                  address_snapshot, answers, description, preferred_start, preferred_end, priority, status, agent_id,
                  first_agent_response_at, sla_due_at, idempotency_key, booking_mode, line_items, estimated_total, has_quote_items)
                VALUES (:num, :cust, :by, :via, CAST(:consent AS text), CASE WHEN CAST(:consent AS text) IS NULL THEN NULL ELSE now() END,
                  CASE WHEN :byAgent THEN NULL ELSE now() END, :cat, :sub, :svc, :city, :loc, :addr, CAST(:snap AS jsonb),
                  CAST(:answers AS jsonb), :desc, :ps, :pe, :prio, :status, :agent,
                  CASE WHEN :byAgent THEN now() ELSE NULL END, now() + make_interval(mins => :sla), :key,
                  :mode, CAST(:lines AS jsonb), :est, :hasQuote)
                RETURNING id""")
                .param("num", number).param("cust", cmd.customerUserId()).param("by", cmd.createdBy()).param("via", cmd.createdVia())
                .param("consent", cmd.consentMethod()).param("byAgent", byAgent).param("cat", ctx.categoryId())
                .param("sub", ctx.subcategoryId()).param("svc", ctx.serviceItemId()).param("city", cityId).param("loc", localityId)
                .param("addr", cmd.addressId()).param("snap", json.write(address)).param("answers", json.write(answers))
                .param("desc", cmd.description()).param("ps", cmd.preferredStart()).param("pe", cmd.preferredEnd())
                .param("prio", priority).param("status", status).param("agent", agentId).param("sla", slaMinutes)
                .param("key", cmd.idempotencyKey()).param("mode", bookingMode).param("lines", json.write(cmd.lineItems()))
                .param("est", cmd.estimatedTotal()).param("hasQuote", cmd.hasQuoteItems()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO request_status_history(request_id, from_status, to_status, actor_user_id, actor_role, reason)
                VALUES (:r, NULL, :s, :a, :role, :reason)""")
                .param("r", id).param("s", status).param("a", cmd.createdBy()).param("role", byAgent ? "AGENT" : "CUSTOMER")
                .param("reason", byAgent ? "Created by agent on behalf of customer (consent: " + cmd.consentMethod() + ")"
                        : switch (bookingMode) {
                            case "SELF" -> "Customer booked menu items at listed prices";
                            case "AGENT_DIRECT" -> "Customer wrote directly to their agent";
                            default -> "Submitted by customer";
                        })
                .update();
        audit.record(cmd.createdBy(), byAgent ? "AGENT" : "CUSTOMER", "REQUEST_CREATED", "REQUEST", id,
                Map.of("requestNumber", number, "createdVia", cmd.createdVia(), "consentMethod", String.valueOf(cmd.consentMethod())));

        if (byAgent) {
            notifications.notifyUser(cmd.customerUserId(), "Your agent created request " + number,
                    "Please review the details and confirm or correct them in the app.", "REQUEST_CONFIRMATION", "REQUEST", id);
        } else {
            notifications.notifyUser(cmd.customerUserId(), "Request " + number + " received",
                    "Your BUZZER agent will review it shortly.", "REQUEST_CREATED", "REQUEST", id);
            if (agentId != null) {
                UUID agentUser = jdbc.sql("SELECT user_id FROM agents WHERE id = :a").param("a", agentId).query(UUID.class).single();
                notifications.notifyUser(agentUser, "New request " + number, switch (bookingMode) {
                            case "SELF" -> "Customer booked at listed prices - assign a verified partner.";
                            case "AGENT_DIRECT" -> "Customer wrote to you directly - please respond.";
                            default -> "A customer in your portfolio submitted a new request.";
                        },
                        "AGENT_NEW_REQUEST", "REQUEST", id);
            }
        }
        return new Created(id, number, status, false);
    }

    /** Agent's first response: starts the review and stops the first-response SLA clock. */
    @Transactional
    public void acknowledge(UUID requestId, UUID agentUserId, UUID agentId) {
        RequestRepository.Row r = requests.lock(requestId);
        jdbc.sql("""
                UPDATE service_requests SET first_agent_response_at = COALESCE(first_agent_response_at, now()),
                  agent_id = COALESCE(agent_id, :a) WHERE id = :id""").param("a", agentId).param("id", requestId).update();
        if (r.statusEnum() == RequestStatus.REQUEST_CREATED) {
            requests.transition(r, RequestStatus.AGENT_REVIEW, agentUserId, "AGENT", "Agent started review");
            notifications.notifyUser(r.customerUserId(), "Your agent is on it", "Your agent has started working on request " + r.requestNumber() + ".",
                    "REQUEST_UPDATE", "REQUEST", requestId);
        }
    }

    /** Customer confirms (or corrects) a request their agent created on their behalf. */
    @Transactional
    public void customerConfirm(UUID requestId, UUID customerUserId, String correction) {
        RequestRepository.Row r = requests.lock(requestId);
        if (!r.customerUserId().equals(customerUserId)) throw BusinessException.forbidden("Not your request");
        jdbc.sql("UPDATE service_requests SET customer_confirmed_at = COALESCE(customer_confirmed_at, now()) WHERE id = :id")
                .param("id", requestId).update();
        if (correction != null && !correction.isBlank()) {
            requests.addMessage(requestId, customerUserId, "CUSTOMER", "Correction: " + correction);
        }
        audit.record(customerUserId, "CUSTOMER", "REQUEST_CONFIRMED_BY_CUSTOMER", "REQUEST", requestId,
                Map.of("correction", correction == null ? "" : correction));
    }

    public Map<String, Object> detail(UUID requestId, String viewerRole) {
        Map<String, Object> base = jdbc.sql(RequestRepository.LIST_SELECT.replace("SELECT r.id,", """
                SELECT r.id, r.customer_user_id, r.agent_id, r.escalated_to_agent_id, r.category_id, r.subcategory_id,
                       r.service_item_id, r.answers::text AS answers, r.address_snapshot::text AS address_snapshot,
                       r.preferred_end, r.consent_method, r.consent_at, r.cancel_reason, c.workflow_template, c.advisory_notice,
                       au.phone AS agent_phone, a.photo_url AS agent_photo, r.line_items::text AS line_items, r.has_quote_items,""")
                .replace("LEFT JOIN agents a ON a.id = r.agent_id", "LEFT JOIN agents a ON a.id = r.agent_id LEFT JOIN users au ON au.id = a.user_id")
                + " WHERE r.id = :id").param("id", requestId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Request"));
        Map<String, Object> out = new LinkedHashMap<>(base);
        out.put("answers", json.tree((String) base.get("answers")));
        out.put("address_snapshot", json.tree((String) base.get("address_snapshot")));
        out.put("line_items", json.tree((String) base.get("line_items")));
        out.put("history", requests.history(requestId));
        out.put("messages", requests.messages(requestId));
        out.put("viewer_role", viewerRole);
        return out;
    }

    public List<Map<String, Object>> customerRequests(UUID customerUserId, boolean openOnly) {
        return jdbc.sql(RequestRepository.LIST_SELECT + """
                WHERE r.customer_user_id = :c AND (NOT :open OR r.status NOT IN ('SETTLED','CANCELLED','REJECTED','EXPIRED','REFUNDED'))
                ORDER BY r.created_at DESC LIMIT 100""").param("c", customerUserId).param("open", openOnly).query().listOfRows();
    }

    public List<Map<String, Object>> agentRequests(UUID agentId, boolean manager, String status, int limit) {
        return jdbc.sql(RequestRepository.LIST_SELECT + """
                WHERE (r.agent_id = :a OR r.escalated_to_agent_id = :a
                       OR (:mgr AND r.agent_id IN (SELECT id FROM agents WHERE manager_id = :a)))
                  AND (CAST(:s AS text) IS NULL OR r.status = CAST(:s AS text))
                ORDER BY CASE WHEN r.first_agent_response_at IS NULL THEN 0 ELSE 1 END, r.sla_due_at NULLS LAST, r.created_at DESC
                LIMIT :l""").param("a", agentId).param("mgr", manager).param("s", status).param("l", Math.min(limit, 500))
                .query().listOfRows();
    }
}
