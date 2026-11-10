package com.buzzer;

import com.buzzer.booking.BookingService;
import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Stage 6 acceptance D: instant service booking (slot + chosen provider), provider calendar, accept / decline, completion evidence. */
@AutoConfigureMockMvc
class ServiceBookingFlowTest extends IntegrationTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired BookingService bookingService;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        jdbc.sql("UPDATE platform_verticals SET enabled = TRUE").update();
        jdbc.sql("UPDATE partners SET availability = 'AVAILABLE'").update();
    }

    private String tapRepair() {
        return jdbc.sql("""
                SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code = 'PLUMBING' AND i.code = 'TAP_REPAIR'""")
                .query(String.class).single();
    }

    private String address(String token) {
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String loc = jdbc.sql("SELECT id::text FROM localities WHERE name = 'Kukatpally'").query(String.class).single();
        return api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "Flat 9, Lake View, Kukatpally", "city_id", city,
                "locality_id", loc, "pincode", "500072")).data().path("id").asText();
    }

    private String partnerId(String phone) {
        return jdbc.sql("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = ?").param("+91" + phone).query(String.class).single();
    }

    /** All bookable slots (start ISO, providers free) across the next days. */
    private List<JsonNode> slots(String customer, String addressId) {
        var res = api.get("/api/v1/customer/services/" + tapRepair() + "/slots?addressId=" + addressId, customer);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        List<JsonNode> out = new ArrayList<>();
        res.data().path("days").forEach(d -> d.path("slots").forEach(out::add));
        return out;
    }

    private JsonNode providersAt(String customer, String addressId, String slot) {
        var res = api.get("/api/v1/customer/services/" + tapRepair() + "/providers?addressId=" + addressId + "&slot=" + slot, customer);
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data();
    }

    private List<String> ids(JsonNode list) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path("id").asText()));
        return out;
    }

    private Map<String, Object> booking(String addressId, String partnerId, String slot, String payment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_item_id", tapRepair());
        body.put("partner_id", partnerId);
        body.put("slot_start", slot);
        body.put("address_id", addressId);
        body.put("payment_method", payment);
        body.put("answers", Map.of("problem_area", "Kitchen", "leak_severity", "Slow drip", "details", "Kitchen tap keeps dripping at night"));
        return body;
    }

    private String assignmentFor(String partnerToken, String requestId) {
        for (JsonNode a : api.get("/api/v1/partner/assignments", partnerToken).data()) {
            if (a.path("request_id").asText().equals(requestId) && List.of("ACTIVE", "CONFIRMED", "IN_PROGRESS").contains(a.path("status").asText())) {
                return a.path("id").asText();
            }
        }
        throw new AssertionError("No live job for " + requestId);
    }

    private JsonNode photo(String token, String assignmentId, String purpose) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/v1/partner/assignments/" + assignmentId + "/evidence")
                .file(new MockMultipartFile("file", purpose.toLowerCase() + ".png", "image/png", PNG)).param("purpose", purpose)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    /** A weekday slot (Mon-Sat, so all three plumbers work) at which at least the given number of providers are free. */
    private String weekdaySlot(List<JsonNode> slots, int minProviders, int skip) {
        int seen = 0;
        for (JsonNode s : slots) {
            OffsetDateTime t = OffsetDateTime.parse(s.path("start").asText());
            if (t.atZoneSameInstant(IST).getDayOfWeek().getValue() == 7 || s.path("providers").asInt() < minProviders) continue;
            if (seen++ == skip) return s.path("start").asText();
        }
        throw new AssertionError("No weekday slot with " + minProviders + " providers in " + slots);
    }

    @Test
    void customerPicksASlotAndAProfessionalWhoAcceptsWorksWithOtpsAndPhotosAndCompletes() throws Exception {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer);
        String slot = weekdaySlot(slots(customer, addr), 3, 0);
        JsonNode providers = providersAt(customer, addr, slot);
        String ramesh = partnerId("9000000021"), quickFix = partnerId("9000000022");
        assertThat(ids(providers)).contains(ramesh, quickFix);
        JsonNode first = providers.get(0);
        assertThat(first.path("unit_price").decimalValue()).isPositive();
        assertThat(first.has("rating_avg")).isTrue();
        assertThat(first.has("jobs_completed")).isTrue();

        // Book Ramesh, pay after the service.
        String key = ApiClient.key();
        var booked = api.post("/api/v1/customer/bookings", customer, booking(addr, ramesh, slot, "PAY_ON_DELIVERY"), key);
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        String requestId = booked.data().path("id").asText();
        assertThat(booked.data().path("status").asText()).isEqualTo("PARTNER_ASSIGNED");
        assertThat(booked.data().path("partner_name").asText()).isEqualTo("Ramesh Plumbing Works");
        assertThat(api.post("/api/v1/customer/bookings", customer, booking(addr, ramesh, slot, "PAY_ON_DELIVERY"), key).data().path("duplicate").asBoolean()).isTrue();
        var row = jdbc.sql("SELECT booking_mode, request_type, handling_mode, preferred_start FROM service_requests WHERE id = ?::uuid").param(requestId).query().singleRow();
        assertThat(row.get("booking_mode")).isEqualTo("PROVIDER_SELECTED");
        assertThat(row.get("request_type")).isEqualTo("SERVICE_BOOKING");
        assertThat(row.get("handling_mode")).isEqualTo("AUTOMATED");
        String bookedTotal = booked.data().path("total").asText();

        // The slot is now taken for Ramesh: another customer can't book him at the same time.
        String other = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String otherAddr = address(other);
        assertThat(ids(providersAt(other, otherAddr, slot))).doesNotContain(ramesh);
        var clash = api.post("/api/v1/customer/bookings", other, booking(otherAddr, ramesh, slot, "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(clash.status()).isEqualTo(409);
        assertThat(clash.errorCode()).isEqualTo("SLOT_UNAVAILABLE");

        // Ramesh declines -> the customer picks another professional for the same slot, at the same price.
        String rameshToken = api.login("9000000021", "PARTNER");
        JsonNode job = null;
        for (JsonNode a : api.get("/api/v1/partner/assignments", rameshToken).data()) if (a.path("request_id").asText().equals(requestId)) job = a;
        assertThat(job.path("slot_start").asText()).isNotBlank();
        assertThat(api.post("/api/v1/partner/assignments/" + job.path("id").asText() + "/decline", rameshToken, Map.of("reason", "Away that day")).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single()).isEqualTo("PARTNER_DECLINED");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM notifications n JOIN service_requests r ON r.customer_user_id = n.user_id
                WHERE r.id = ?::uuid AND n.title LIKE 'Choose another professional%'""").param(requestId).query(Integer.class).single()).isEqualTo(1);
        JsonNode alternatives = api.get("/api/v1/customer/requests/" + requestId + "/alternatives", customer).data();
        assertThat(ids(alternatives)).contains(quickFix).doesNotContain(ramesh);
        var rebooked = api.post("/api/v1/customer/requests/" + requestId + "/rebook", customer, Map.of("partner_id", quickFix));
        assertThat(rebooked.status()).as(rebooked.body().toString()).isEqualTo(200);
        assertThat(rebooked.data().path("status").asText()).isEqualTo("PARTNER_ASSIGNED");
        assertThat(rebooked.data().path("partner_name").asText()).isEqualTo("Quick Fix Plumbers");
        assertThat(jdbc.sql("SELECT total_amount::text FROM quotations WHERE request_id = ?::uuid AND status = 'ACCEPTED'").param(requestId)
                .query(String.class).single()).isEqualTo(new java.math.BigDecimal(bookedTotal).setScale(2).toPlainString());

        // Quick Fix accepts -> booking at the chosen time.
        String quickFixToken = api.login("9000000022", "PARTNER");
        String assignment = assignmentFor(quickFixToken, requestId);
        assertThat(api.post("/api/v1/partner/assignments/" + assignment + "/confirm", quickFixToken, Map.of()).status()).isEqualTo(200);
        OffsetDateTime scheduled = jdbc.sql("SELECT scheduled_at FROM bookings WHERE request_id = ?::uuid").param(requestId).query(OffsetDateTime.class).single();
        assertThat(scheduled.toInstant()).isEqualTo(OffsetDateTime.parse(slot).toInstant());
        // The job shows on Quick Fix's calendar.
        JsonNode cal = api.get("/api/v1/partner/schedule", quickFixToken).data();
        assertThat(cal.path("upcoming_jobs").toString()).contains(requestId);

        // Work: arrive, START OTP, before photo; completion needs an after photo when the setting is on.
        jdbc.sql("UPDATE platform_settings SET value = 'true' WHERE key = 'booking.completion_photo_required'").update();
        try {
            api.post("/api/v1/partner/assignments/" + assignment + "/arrived", quickFixToken, Map.of());
            String startOtp = api.get("/api/v1/customer/requests/" + requestId + "/service-otp?purpose=START", customer).data().path("otp").asText();
            assertThat(api.post("/api/v1/partner/assignments/" + assignment + "/start", quickFixToken, Map.of("otp", startOtp)).status()).isEqualTo(200);
            assertThat(photo(quickFixToken, assignment, "BEFORE").path("data").path("purpose").asText()).isEqualTo("BEFORE");
            // Other partners can't attach photos to this job.
            assertThat(photo(rameshToken, assignment, "AFTER").path("error").path("code").asText()).isEqualTo("NOT_FOUND");
            var early = api.post("/api/v1/partner/assignments/" + assignment + "/request-completion", quickFixToken, Map.of());
            assertThat(early.status()).isEqualTo(400);
            assertThat(early.body().toString()).contains("after photo");
            photo(quickFixToken, assignment, "AFTER");
            assertThat(api.post("/api/v1/partner/assignments/" + assignment + "/request-completion", quickFixToken, Map.of()).status()).isEqualTo(200);
        } finally {
            jdbc.sql("UPDATE platform_settings SET value = 'false' WHERE key = 'booking.completion_photo_required'").update();
        }
        String doneOtp = api.get("/api/v1/customer/requests/" + requestId + "/service-otp?purpose=COMPLETE", customer).data().path("otp").asText();
        assertThat(api.post("/api/v1/partner/assignments/" + assignment + "/complete", quickFixToken, Map.of("otp", doneOtp)).status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single())
                .isIn("COMPLETED", "SETTLEMENT_PENDING", "SETTLED");

        // The customer sees the professional's photos; the timeline tells the whole story.
        JsonNode detail = api.get("/api/v1/customer/requests/" + requestId, customer).data();
        List<String> purposes = new ArrayList<>();
        detail.path("attachments").forEach(a -> purposes.add(a.path("purpose").asText()));
        assertThat(purposes).contains("BEFORE", "AFTER");
        List<String> timeline = jdbc.sql("SELECT reason FROM request_status_history WHERE request_id = ?::uuid ORDER BY created_at, id")
                .param(requestId).query(String.class).list();
        assertThat(timeline).anyMatch(t -> t.startsWith("Customer booked a time slot"));
        assertThat(timeline).anyMatch(t -> t.startsWith("Customer chose Ramesh Plumbing Works for"));
        assertThat(timeline).anyMatch(t -> t.contains("Customer chose Quick Fix Plumbers instead"));
        assertThat(timeline).anyMatch(t -> t.startsWith("After photo"));
    }

    @Test
    void providerCalendarHoursAndTimeOffDecideWhoCanBeBooked() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer);
        String slot = weekdaySlot(slots(customer, addr), 1, 2);
        String aqua = partnerId("9000000023");
        String aquaToken = api.login("9000000023", "PARTNER");
        Map<String, Object> defaults = Map.of("weekly", List.of(1, 2, 3, 4, 5, 6).stream()
                .map(d -> Map.of("weekday", d, "start_time", "09:00", "end_time", "19:00")).toList());
        try {
            assertThat(ids(providersAt(customer, addr, slot))).contains(aqua);
            // Aqua Care only works Sunday evenings now -> not offered on a weekday.
            var saved = api.put("/api/v1/partner/schedule", aquaToken, Map.of("weekly", List.of(Map.of("weekday", 7, "start_time", "18:00", "end_time", "19:00"))));
            assertThat(saved.status()).as(saved.body().toString()).isEqualTo(200);
            assertThat(ids(providersAt(customer, addr, slot))).doesNotContain(aqua);
            // Invalid hours are refused.
            assertThat(api.put("/api/v1/partner/schedule", aquaToken, Map.of("weekly", List.of(Map.of("weekday", 2, "start_time", "18:00", "end_time", "09:00")))).status())
                    .isEqualTo(400);
            assertThat(api.put("/api/v1/partner/schedule", aquaToken, defaults).status()).isEqualTo(200);
            assertThat(ids(providersAt(customer, addr, slot))).contains(aqua);

            // A day off hides the provider for that day only.
            LocalDate day = OffsetDateTime.parse(slot).atZoneSameInstant(IST).toLocalDate();
            JsonNode off = api.post("/api/v1/partner/schedule/time-off", aquaToken, Map.of("starts_on", day.toString(), "reason", "Family function")).data();
            assertThat(ids(providersAt(customer, addr, slot))).doesNotContain(aqua);
            String offId = off.path("time_off").get(0).path("id").asText();
            api.call("DELETE", "/api/v1/partner/schedule/time-off/" + offId, aquaToken, null, null);
            assertThat(ids(providersAt(customer, addr, slot))).contains(aqua);
            // Admins can see a provider's calendar.
            assertThat(api.get("/api/v1/admin/partners/" + aqua + "/schedule", api.login("9000000001", "ADMIN")).data().path("weekly").size()).isEqualTo(6);
        } finally {
            api.put("/api/v1/partner/schedule", aquaToken, defaults);
        }
    }

    @Test
    void bookingsTheProviderDoesNotAcceptInTimeGoBackToTheCustomerAndFreeTheSlot() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer);
        String slot = weekdaySlot(slots(customer, addr), 1, 4);
        String aqua = partnerId("9000000023");
        assertThat(ids(providersAt(customer, addr, slot))).contains(aqua);
        var booked = api.post("/api/v1/customer/bookings", customer, booking(addr, aqua, slot, "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(200);
        String requestId = booked.data().path("id").asText();
        assertThat(ids(providersAt(customer, addr, slot))).doesNotContain(aqua);

        jdbc.sql("UPDATE assignments SET expires_at = now() - interval '1 minute' WHERE request_id = ?::uuid AND status = 'ACTIVE'").param(requestId).update();
        assertThat(bookingService.expireUnconfirmed()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single()).isEqualTo("PARTNER_DECLINED");
        assertThat(jdbc.sql("SELECT status FROM assignments WHERE request_id = ?::uuid").param(requestId).query(String.class).single()).isEqualTo("EXPIRED");
        // The slot is free again for that provider, and the customer is offered the others.
        assertThat(ids(api.get("/api/v1/customer/requests/" + requestId + "/alternatives", customer).data())).doesNotContain(aqua);
        assertThat(ids(providersAt(customer, addr, slot))).contains(aqua);
    }

    @Test
    void onlyFixedPriceServicesCanBeBookedInstantlyAndOnlyOnTheSlotGrid() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer);
        String quoteOnly = jdbc.sql("SELECT id::text FROM service_items WHERE pricing_type = 'QUOTE_REQUIRED' AND active LIMIT 1").query(String.class).single();
        assertThat(api.get("/api/v1/customer/services/" + quoteOnly + "/slots?addressId=" + addr, customer).status()).isEqualTo(400);
        // Off-grid time (07:13 tomorrow) is refused.
        String odd = LocalDate.now(IST).plusDays(1).atTime(7, 13).atZone(IST).toOffsetDateTime().toString();
        var res = api.post("/api/v1/customer/bookings", customer, booking(addr, partnerId("9000000021"), odd, "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.errorCode()).isEqualTo("SLOT_UNAVAILABLE");
        // Someone else's address can't be used.
        String stranger = api.login(ApiClient.randomPhone(), "CUSTOMER");
        assertThat(api.get("/api/v1/customer/services/" + tapRepair() + "/slots?addressId=" + addr, stranger).status()).isEqualTo(400);
    }
}
