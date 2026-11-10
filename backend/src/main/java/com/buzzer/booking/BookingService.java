package com.buzzer.booking;

import com.buzzer.assignment.AssignmentService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BusinessIds;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.SettingsService;
import com.buzzer.marketplace.MarketplaceSql;
import com.buzzer.notification.NotificationService;
import com.buzzer.partner.PartnerEligibility;
import com.buzzer.payment.PaymentService;
import com.buzzer.request.RequestRepository;
import com.buzzer.request.RequestService;
import com.buzzer.request.RequestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Instant service booking: the customer picks a time slot and a provider (rating, distance, price, jobs done) and books.
 * Under the hood it is the existing request lifecycle - a price-list quotation from the chosen provider is accepted and
 * paid (online, or pay after the service), the provider gets an exclusive assignment and must accept it within the
 * confirmation window, then works with the existing START / COMPLETE OTPs (plus optional before / after photos).
 * If the provider declines or doesn't answer in time, the customer picks another provider for the same slot at the
 * price already booked.
 */
@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a");

    public record BookInput(UUID serviceItemId, UUID partnerId, OffsetDateTime slotStart, UUID addressId, Map<String, Object> answers,
                            String description, String paymentMethod, Integer quantity) {}

    /** Bookable service: its price comes from the provider's approved price list, else the catalogue price. */
    private record Svc(UUID id, String name, UUID subcategoryId, UUID categoryId, String pricingType, BigDecimal basePrice, BigDecimal minPrice,
                       String unit, int durationMinutes, boolean payOnDeliveryAllowed, boolean active) {
        BigDecimal listedPrice() {
            return switch (pricingType) {
                case "QUOTE_REQUIRED", "API_PRICED" -> null;
                case "RANGE" -> minPrice;
                default -> basePrice;
            };
        }
    }

    private record Place(UUID addressId, UUID cityId, UUID localityId, BigDecimal lat, BigDecimal lng) {}

    private final JdbcClient jdbc;
    private final BusinessIds ids;
    private final SettingsService settings;
    private final AvailabilityService availability;
    private final RequestService requestService;
    private final RequestRepository requests;
    private final PartnerEligibility eligibility;
    private final PaymentService payments;
    private final AssignmentService assignments;
    private final NotificationService notifications;
    private final AuditService audit;

    public BookingService(JdbcClient jdbc, BusinessIds ids, SettingsService settings, AvailabilityService availability, RequestService requestService,
                          RequestRepository requests, PartnerEligibility eligibility, PaymentService payments, AssignmentService assignments,
                          NotificationService notifications, AuditService audit) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.settings = settings;
        this.availability = availability;
        this.requestService = requestService;
        this.requests = requests;
        this.eligibility = eligibility;
        this.payments = payments;
        this.assignments = assignments;
        this.notifications = notifications;
        this.audit = audit;
    }

    // ===== Slots and provider matches ======================================================================

    /** Next days with every slot in which at least one matching provider is free (count and lowest price). */
    public Map<String, Object> slots(UUID customer, UUID serviceItemId, UUID addressId, LocalDate from, int days) {
        Svc svc = service(serviceItemId);
        Place place = place(customer, addressId);
        List<Map<String, Object>> providers = candidates(svc, place, Set.of());
        ZoneId zone = availability.zone();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = from == null || from.isBefore(today) ? today : from;
        int horizon = Math.max(1, settings.getInt("booking.days_ahead", 7));
        LocalDate last = today.plusDays(horizon - 1);
        int n = Math.min(Math.max(days, 1), horizon);
        List<OffsetDateTime> grid = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(last) && !d.isAfter(start.plusDays(n - 1)); d = d.plusDays(1)) grid.addAll(grid(d, svc.durationMinutes()));
        AvailabilityService.Calendars cal = grid.isEmpty() ? null
                : availability.load(providers.stream().map(p -> (UUID) p.get("id")).toList(), grid.getFirst(), grid.getLast().plusDays(1));
        Map<LocalDate, List<Map<String, Object>>> byDay = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(last) && !d.isAfter(start.plusDays(n - 1)); d = d.plusDays(1)) byDay.put(d, new ArrayList<>());
        for (OffsetDateTime s : grid) {
            int free = 0;
            BigDecimal min = null;
            for (Map<String, Object> p : providers) {
                if (!cal.isFree((UUID) p.get("id"), s, svc.durationMinutes())) continue;
                free++;
                BigDecimal price = (BigDecimal) p.get("unit_price");
                if (min == null || price.compareTo(min) < 0) min = price;
            }
            if (free == 0) continue;
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("start", s);
            slot.put("end", s.plusMinutes(svc.durationMinutes()));
            slot.put("label", s.atZoneSameInstant(zone).toLocalTime().toString());
            slot.put("providers", free);
            slot.put("from_price", min);
            byDay.get(s.atZoneSameInstant(zone).toLocalDate()).add(slot);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byDay.forEach((d, list) -> out.add(Map.of("date", d.toString(), "label", d.equals(today) ? "Today" : d.equals(today.plusDays(1)) ? "Tomorrow"
                : d.format(DateTimeFormatter.ofPattern("EEE d MMM")), "slots", list)));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("service", Map.of("id", svc.id(), "name", svc.name(), "duration_minutes", svc.durationMinutes(), "pricing_type", svc.pricingType()));
        res.put("time_zone", zone.getId());
        res.put("matching_providers", providers.size());
        res.put("days", out);
        return res;
    }

    /** Providers free at the slot, best first: rating, then distance, then price. */
    public List<Map<String, Object>> providers(UUID customer, UUID serviceItemId, UUID addressId, OffsetDateTime slot, int quantity) {
        Svc svc = service(serviceItemId);
        Place place = place(customer, addressId);
        checkSlot(slot, svc.durationMinutes());
        return freeAt(svc, place, slot, Set.of(), qty(quantity));
    }

    private List<Map<String, Object>> freeAt(Svc svc, Place place, OffsetDateTime slot, Set<UUID> exclude, int quantity) {
        List<Map<String, Object>> providers = candidates(svc, place, exclude);
        AvailabilityService.Calendars cal = availability.load(providers.stream().map(p -> (UUID) p.get("id")).toList(), slot, slot.plusDays(1));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> p : providers) {
            if (!cal.isFree((UUID) p.get("id"), slot, svc.durationMinutes())) continue;
            Map<String, Object> m = new LinkedHashMap<>(p);
            m.put("total", ((BigDecimal) p.get("unit_price")).multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP));
            m.put("quantity", quantity);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> ((BigDecimal) m.get("rating_avg")).negate())
                .thenComparing(m -> m.get("distance_km") == null ? new BigDecimal("100000") : (BigDecimal) m.get("distance_km"))
                .thenComparing(m -> (BigDecimal) m.get("unit_price")));
        return out;
    }

    /** Approved, verified, open service providers for the service and area, with their price. */
    private List<Map<String, Object>> candidates(Svc svc, Place place, Set<UUID> exclude) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT p.id, p.display_name, p.business_name, p.partner_type, p.rating_avg, p.rating_count, loc.distance_km,
                       (SELECT count(*) FROM assignments a WHERE a.partner_id = p.id AND a.status = 'COMPLETED') AS jobs_completed,
                       (SELECT ppi.approved_price FROM partner_price_items ppi WHERE ppi.partner_id = p.id AND ppi.service_item_id = :svc
                          AND ppi.active AND ppi.approved_price IS NOT NULL) AS own_price,
                       (SELECT string_agg(DISTINCT l.name, ', ') FROM partner_service_areas sa JOIN localities l ON l.id = sa.locality_id
                          WHERE sa.partner_id = p.id) AS service_areas
                FROM partners p
                """ + MarketplaceSql.NEAREST_LOCATION + """

                WHERE p.registration_status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND p.availability = 'AVAILABLE' AND p.city_id = :city
                  AND (CAST(:loc AS uuid) IS NULL OR NOT EXISTS (SELECT 1 FROM partner_service_areas sa WHERE sa.partner_id = p.id)
                       OR EXISTS (SELECT 1 FROM partner_service_areas sa WHERE sa.partner_id = p.id AND sa.locality_id = CAST(:loc AS uuid)))
                  AND EXISTS (SELECT 1 FROM partner_capabilities pc WHERE pc.partner_id = p.id AND pc.status = 'APPROVED'
                              AND pc.subcategory_id = :sub AND (pc.service_item_id IS NULL OR pc.service_item_id = :svc))
                  AND EXISTS (SELECT 1 FROM partner_roles r WHERE r.partner_id = p.id AND r.status = 'APPROVED' AND r.role = 'SERVICE_PROVIDER')
                  AND (loc.distance_km IS NULL OR loc.distance_km <= loc.service_radius_km)
                ORDER BY p.rating_avg DESC, p.display_name""")
                .param("lat", place.lat()).param("lng", place.lng()).param("svc", svc.id()).param("city", place.cityId())
                .param("loc", place.localityId()).param("sub", svc.subcategoryId()).query().listOfRows();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (exclude.contains((UUID) r.get("id"))) continue;
            BigDecimal price = r.get("own_price") != null ? (BigDecimal) r.get("own_price") : svc.listedPrice();
            if (price == null) continue;
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.remove("own_price");
            m.put("unit_price", price.setScale(2, RoundingMode.HALF_UP));
            m.put("price_source", r.get("own_price") != null ? "PROVIDER_PRICE" : "LISTED_PRICE");
            out.add(m);
        }
        return out;
    }

    // ===== Booking =================================================================================================

    @Transactional
    public Map<String, Object> book(UUID customer, BookInput in, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80) throw BusinessException.invalid("Idempotency-Key header is required");
        String key = "booking:" + idempotencyKey.trim();
        UUID dup = jdbc.sql("SELECT id FROM service_requests WHERE customer_user_id = :c AND idempotency_key = :k").param("c", customer).param("k", key)
                .query(UUID.class).optional().orElse(null);
        if (dup != null) return result(dup, null, true);
        if (in == null || in.serviceItemId() == null || in.partnerId() == null || in.slotStart() == null) {
            throw BusinessException.invalid("Choose a service, a time and a professional");
        }
        if (in.addressId() == null) throw BusinessException.invalid("Choose the service address");
        Svc svc = service(in.serviceItemId());
        Place place = place(customer, in.addressId());
        checkSlot(in.slotStart(), svc.durationMinutes());
        int quantity = qty(in.quantity());
        if (in.description() != null && in.description().length() > 2000) throw BusinessException.invalid("Description is too long");
        // Serialise bookings of one provider: the second customer for the same slot sees it taken.
        String partnerName = jdbc.sql("SELECT display_name FROM partners WHERE id = :p FOR UPDATE").param("p", in.partnerId()).query(String.class)
                .optional().orElseThrow(() -> BusinessException.notFound("Provider"));
        Map<String, Object> match = freeAt(svc, place, in.slotStart(), Set.of(), quantity).stream()
                .filter(p -> in.partnerId().equals(p.get("id"))).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SLOT_UNAVAILABLE, partnerName + " is no longer free at that time - please pick another slot or professional"));
        BigDecimal unit = (BigDecimal) match.get("unit_price");
        BigDecimal total = (BigDecimal) match.get("total");
        OffsetDateTime end = in.slotStart().plusMinutes(svc.durationMinutes());
        String when = in.slotStart().atZoneSameInstant(availability.zone()).format(LABEL);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("service_item_id", svc.id().toString());
        line.put("name", svc.name());
        line.put("quantity", quantity);
        line.put("unit", svc.unit());
        line.put("pricing_type", svc.pricingType());
        line.put("unit_price", unit);
        line.put("line_total", total);
        RequestService.Created created = requestService.create(new RequestService.CreateCommand(customer, customer, "CUSTOMER_APP", null, svc.subcategoryId(),
                svc.id(), in.addressId(), in.answers(), in.description(), in.slotStart(), end, "NORMAL", key, null, "PROVIDER_SELECTED",
                List.of(line), total, false, false));
        if (created.duplicate()) return result(created.id(), null, true);

        UUID quotationId = priceListQuote(created.id(), in.partnerId(), customer, svc, quantity, unit, total, in.slotStart(),
                "PROVIDER_PRICE".equals(match.get("price_source")) ? "From the professional's approved price list" : "BUZZER listed price");
        RequestRepository.Row r = requests.lock(created.id());
        requests.transition(r, RequestStatus.PARTNER_QUOTATION, customer, "CUSTOMER", "Customer chose " + partnerName + " for " + when);
        requests.transition(requests.lock(created.id()), RequestStatus.CUSTOMER_APPROVAL, null, "SYSTEM",
                "Price Rs " + total.toPlainString() + " (" + quantity + " x Rs " + unit.toPlainString() + ", "
                        + ("PROVIDER_PRICE".equals(match.get("price_source")) ? "professional's approved price" : "BUZZER listed price") + ")");
        Map<String, Object> accepted = payments.acceptQuotation(created.id(), quotationId, customer, in.paymentMethod() == null ? "" : in.paymentMethod().toUpperCase(),
                "booking-pay:" + idempotencyKey.trim());
        audit.record(customer, "CUSTOMER", "SERVICE_BOOKED", "REQUEST", created.id(), Map.of("partner", in.partnerId().toString(), "slot", in.slotStart().toString(),
                "total", total.toPlainString()));
        return result(created.id(), accepted.get("payment"), false);
    }

    /** Providers the customer can switch to after a decline / no answer: same service and slot, not already tried. */
    public List<Map<String, Object>> alternatives(UUID requestId, UUID customer) {
        Ctx c = ctx(requestId, customer);
        return freeAt(service(c.serviceItemId()), place(customer, c.addressId()), c.slotStart(), tried(requestId), c.quantity());
    }

    /** Switch to another provider for the same slot. The price already booked (and any payment) carries over. */
    @Transactional
    public Map<String, Object> rebook(UUID requestId, UUID partnerId, UUID customer) {
        RequestRepository.Row r = requests.lock(requestId);
        Ctx c = ctx(requestId, customer);
        if (r.statusEnum() != RequestStatus.PARTNER_DECLINED && r.statusEnum() != RequestStatus.NO_SHOW) {
            throw BusinessException.transition(r.status(), "PARTNER_ASSIGNED");
        }
        if (partnerId == null) throw BusinessException.invalid("Choose a professional");
        String name = jdbc.sql("SELECT display_name FROM partners WHERE id = :p FOR UPDATE").param("p", partnerId).query(String.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Provider"));
        Svc svc = service(c.serviceItemId());
        boolean free = freeAt(svc, place(customer, c.addressId()), c.slotStart(), tried(requestId), c.quantity()).stream()
                .anyMatch(p -> partnerId.equals(p.get("id")));
        if (!free || !eligibility.isEligible(r, partnerId)) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE, name + " is not free at that time - please pick another professional");
        }
        BigDecimal booked = jdbc.sql("""
                SELECT amount FROM payments WHERE request_id = :r AND status IN ('SUCCEEDED','COLLECT_ON_DELIVERY','PENDING','CREATED')
                ORDER BY created_at DESC LIMIT 1""").param("r", requestId).query(BigDecimal.class).optional()
                .orElseGet(() -> jdbc.sql("SELECT estimated_total FROM service_requests WHERE id = :r").param("r", requestId).query(BigDecimal.class).single());
        UUID quotationId = priceListQuote(requestId, partnerId, customer, svc, c.quantity(),
                booked.divide(BigDecimal.valueOf(c.quantity()), 2, RoundingMode.HALF_UP), booked, c.slotStart(), "Price already booked by the customer (honoured)");
        assignments.assignExclusive(requestId, quotationId, customer, "CUSTOMER", "Customer chose " + name + " instead (same time, same price)");
        audit.record(customer, "CUSTOMER", "SERVICE_REBOOKED", "REQUEST", requestId, Map.of("partner", partnerId.toString()));
        return result(requestId, null, false);
    }

    // ===== Unanswered bookings ==========================================================================================

    @Scheduled(fixedDelayString = "${buzzer.booking.poll-ms:60000}", initialDelay = 45000)
    public void scheduledSweep() {
        try {
            int n = expireUnconfirmed();
            if (n > 0) log.info("Released {} unconfirmed instant booking(s)", n);
        } catch (Exception e) {
            log.warn("Booking sweep failed: {}", e.getMessage());
        }
    }

    /** Instant bookings the provider didn't accept in time go back to the customer to choose another provider. */
    @Transactional
    public int expireUnconfirmed() {
        record A(UUID id, UUID requestId, UUID partnerId, UUID quotationId, String partnerName) {}
        List<A> due = jdbc.sql("""
                SELECT a.id, a.request_id, a.partner_id, a.quotation_id, p.display_name AS partner_name FROM assignments a
                JOIN service_requests r ON r.id = a.request_id JOIN partners p ON p.id = a.partner_id
                WHERE a.status = 'ACTIVE' AND a.expires_at < now() AND r.booking_mode = 'PROVIDER_SELECTED'
                ORDER BY a.expires_at LIMIT 100 FOR UPDATE OF a SKIP LOCKED""").query(A.class).list();
        int minutes = settings.getInt("assignment.confirm_timeout_minutes", 60);
        for (A a : due) {
            RequestRepository.Row r = requests.lock(a.requestId());
            if (r.statusEnum() != RequestStatus.PARTNER_ASSIGNED) continue;
            String reason = a.partnerName() + " did not accept within " + minutes + " minutes";
            jdbc.sql("UPDATE assignments SET status = 'EXPIRED', cancelled_by = 'SYSTEM', cancel_reason = :r, updated_at = now() WHERE id = :id")
                    .param("r", reason).param("id", a.id()).update();
            jdbc.sql("UPDATE quotations SET status = 'CLOSED' WHERE id = :q").param("q", a.quotationId()).update();
            jdbc.sql("UPDATE partner_invitations SET status = 'EXPIRED', closed_at = now() WHERE request_id = :r AND partner_id = :p")
                    .param("r", a.requestId()).param("p", a.partnerId()).update();
            requests.transition(r, RequestStatus.PARTNER_DECLINED, null, "SYSTEM", reason + " - customer can choose another professional");
            notifications.notifyUser(r.customerUserId(), "Choose another professional - " + r.requestNumber(),
                    a.partnerName() + " couldn't confirm in time. Pick another professional for the same time - your price stays the same.",
                    "REQUEST_UPDATE", "REQUEST", r.id());
            notifications.notifyUser(jdbc.sql("SELECT user_id FROM partners WHERE id = :p").param("p", a.partnerId()).query(UUID.class).single(),
                    "Booking released - " + r.requestNumber(), "You didn't accept the booking in time, so it was offered to another professional.",
                    "PARTNER_ASSIGNMENT", "ASSIGNMENT", a.id());
        }
        return due.size();
    }

    // ===== helpers ======================================================================================================

    private UUID priceListQuote(UUID requestId, UUID partnerId, UUID by, Svc svc, int quantity, BigDecimal unit, BigDecimal total, OffsetDateTime slot,
                                String note) {
        UUID invitationId = jdbc.sql("""
                INSERT INTO partner_invitations(invitation_number, request_id, partner_id, invited_by, status) VALUES (:n, :r, :p, :by, 'QUOTED')
                ON CONFLICT (request_id, partner_id) DO UPDATE SET status = 'QUOTED', closed_at = NULL RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.INVITATION)).param("r", requestId).param("p", partnerId).param("by", by)
                .query(UUID.class).single();
        return jdbc.sql("""
                INSERT INTO quotations(quotation_number, request_id, invitation_id, partner_id, scope_of_work, labour_amount, total_amount,
                  estimated_arrival, valid_until, pay_on_delivery_allowed, status, shared_at, source, agent_note)
                VALUES (:n, :r, :i, :p, :scope, :t, :t, :eta, now() + interval '24 hours', :pod, 'SHARED', now(), 'PRICE_LIST', :note) RETURNING id""")
                .param("n", ids.next(BusinessIds.Kind.QUOTATION)).param("r", requestId).param("i", invitationId).param("p", partnerId)
                .param("scope", quantity + " x " + svc.name() + " @ Rs " + unit.toPlainString()).param("t", total).param("eta", slot)
                .param("pod", svc.payOnDeliveryAllowed()).param("note", note).query(UUID.class).single();
    }

    private Map<String, Object> result(UUID requestId, Object payment, boolean duplicate) {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.sql("""
                SELECT r.id, r.request_number, r.status, r.preferred_start AS slot_start, r.preferred_end AS slot_end, r.estimated_total AS total,
                       (SELECT p.display_name FROM assignments a JOIN partners p ON p.id = a.partner_id WHERE a.request_id = r.id
                        ORDER BY a.created_at DESC LIMIT 1) AS partner_name,
                       (SELECT p.display_name FROM quotations q JOIN partners p ON p.id = q.partner_id WHERE q.request_id = r.id AND q.status = 'ACCEPTED'
                        LIMIT 1) AS chosen_partner
                FROM service_requests r WHERE r.id = :id""").param("id", requestId).query().singleRow());
        out.put("payment", payment);
        out.put("duplicate", duplicate);
        return out;
    }

    private record Ctx(UUID serviceItemId, UUID addressId, OffsetDateTime slotStart, int quantity) {}

    private Ctx ctx(UUID requestId, UUID customer) {
        record R(UUID customerUserId, String bookingMode, UUID serviceItemId, UUID addressId, OffsetDateTime preferredStart, String lineItems) {}
        R r = jdbc.sql("SELECT customer_user_id, booking_mode, service_item_id, address_id, preferred_start, line_items::text AS line_items FROM service_requests WHERE id = :id")
                .param("id", requestId).query(R.class).optional().orElseThrow(() -> BusinessException.notFound("Request"));
        if (!r.customerUserId().equals(customer)) throw BusinessException.notFound("Request");
        if (!"PROVIDER_SELECTED".equals(r.bookingMode()) || r.preferredStart() == null) throw BusinessException.invalid("This request wasn't booked with a chosen time");
        int quantity = 1;
        if (r.lineItems() != null) {
            var m = java.util.regex.Pattern.compile("\"quantity\"\\s*:\\s*(\\d+)").matcher(r.lineItems());
            if (m.find()) quantity = Integer.parseInt(m.group(1));
        }
        return new Ctx(r.serviceItemId(), r.addressId(), r.preferredStart(), quantity);
    }

    private Set<UUID> tried(UUID requestId) {
        return Set.copyOf(jdbc.sql("SELECT DISTINCT partner_id FROM assignments WHERE request_id = :r").param("r", requestId).query(UUID.class).list());
    }

    private Svc service(UUID serviceItemId) {
        if (serviceItemId == null) throw BusinessException.invalid("Choose a service");
        Svc s = jdbc.sql("""
                SELECT i.id, i.name, i.subcategory_id, s.category_id, i.pricing_type, i.base_price, i.min_price, i.unit, i.duration_minutes,
                       i.pay_on_delivery_allowed, (i.active AND s.active AND c.active AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)) AS active
                FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id WHERE i.id = :id""")
                .param("id", serviceItemId).query(Svc.class).optional().orElseThrow(() -> BusinessException.notFound("Service"));
        if (!s.active()) throw BusinessException.notFound("Service");
        if (s.listedPrice() == null) throw BusinessException.invalid("This service is priced by quotation - send a request to get quotes");
        return s;
    }

    private Place place(UUID customer, UUID addressId) {
        if (addressId == null) throw BusinessException.invalid("Choose the service address");
        return jdbc.sql("""
                SELECT a.id AS address_id, a.city_id, a.locality_id, COALESCE(a.latitude, l.latitude) AS lat, COALESCE(a.longitude, l.longitude) AS lng
                FROM addresses a LEFT JOIN localities l ON l.id = a.locality_id WHERE a.id = :a AND a.user_id = :u AND a.deleted_at IS NULL""")
                .param("a", addressId).param("u", customer).query(Place.class).optional().orElseThrow(() -> BusinessException.invalid("Address not found"));
    }

    /** The slot grid of one day (local time), from the earliest allowed start. */
    private List<OffsetDateTime> grid(LocalDate day, int durationMinutes) {
        ZoneId zone = availability.zone();
        int step = Math.max(15, settings.getInt("booking.slot_minutes", 60));
        int startHour = settings.getInt("booking.day_start_hour", 8);
        int endHour = settings.getInt("booking.day_end_hour", 20);
        OffsetDateTime earliest = OffsetDateTime.now().plusMinutes(settings.getInt("booking.min_lead_minutes", 90));
        List<OffsetDateTime> out = new ArrayList<>();
        ZonedDateTime t = day.atTime(startHour, 0).atZone(zone);
        ZonedDateTime limit = day.atTime(Math.min(endHour, 23), endHour >= 24 ? 59 : 0).atZone(zone);
        while (!t.plusMinutes(durationMinutes).isAfter(limit)) {
            if (!t.toOffsetDateTime().isBefore(earliest)) out.add(t.toOffsetDateTime());
            t = t.plusMinutes(step);
        }
        return out;
    }

    /** A slot must be on the grid, within the booking horizon and far enough ahead. */
    private void checkSlot(OffsetDateTime slot, int durationMinutes) {
        if (slot == null) throw BusinessException.invalid("Choose a time");
        LocalDate day = slot.atZoneSameInstant(availability.zone()).toLocalDate();
        LocalDate today = LocalDate.now(availability.zone());
        if (day.isAfter(today.plusDays(Math.max(1, settings.getInt("booking.days_ahead", 7)) - 1)) || day.isBefore(today)
                || grid(day, durationMinutes).stream().noneMatch(s -> s.toInstant().equals(slot.toInstant()))) {
            throw new BusinessException(ErrorCode.SLOT_UNAVAILABLE, "That time can't be booked - please pick one of the available slots");
        }
    }

    private static int qty(Integer quantity) {
        int q = quantity == null ? 1 : quantity;
        if (q < 1 || q > 50) throw BusinessException.invalid("Quantity must be 1-50");
        return q;
    }
}
