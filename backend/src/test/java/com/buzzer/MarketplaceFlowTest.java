package com.buzzer;

import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5 acceptance: A (direct purchase), B (multi-vendor RFQ and quote acceptance), C (vendor item-wise quotation),
 * I (admin vendor approval) and M (vendors / customers only see their own orders and RFQs).
 */
@AutoConfigureMockMvc
class MarketplaceFlowTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        jdbc.sql("UPDATE platform_verticals SET enabled = TRUE").update();
        jdbc.sql("UPDATE partners SET availability = 'AVAILABLE'").update();
    }

    private String locality(String name) {
        return jdbc.sql("SELECT id::text FROM localities WHERE name = ?").param(name).query(String.class).single();
    }

    private String address(String token, String localityName) {
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        var res = api.post("/api/v1/customer/addresses", token, Map.of("label", "HOME", "line1", "Flat 4, Lake View, " + localityName,
                "city_id", city, "locality_id", locality(localityName), "pincode", "500072"));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data().path("id").asText();
    }

    private String productId(String code) {
        return jdbc.sql("SELECT id::text FROM products WHERE code = ?").param(code).query(String.class).single();
    }

    private String listing(String phone, String sku) {
        return jdbc.sql("""
                SELECT l.id::text FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id JOIN partners p ON p.id = l.partner_id
                JOIN users u ON u.id = p.user_id WHERE u.phone = ? AND v.sku = ?""").param("+91" + phone).param(sku).query(String.class).single();
    }

    private String variant(String sku) {
        return jdbc.sql("SELECT id::text FROM product_variants WHERE sku = ?").param(sku).query(String.class).single();
    }

    private List<String> texts(JsonNode list, String field) {
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.path(field).asText()));
        return out;
    }

    private void simulate(String customer, String paymentId, String outcome) {
        var res = api.post("/api/v1/customer/payments/" + paymentId + "/simulate", customer, Map.of("outcome", outcome));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
    }

    // ----- A + M: direct purchase --------------------------------------------------------------------------

    @Test
    void customerComparesShopsBuysFromOneShopAndTheShopFulfilsWithTheDeliveryCode() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String home = address(customer, "Kukatpally");
        String kukatpally = locality("Kukatpally");

        // Product search: stemming ("bulbs") and price comparison across shops, nearest shops first.
        JsonNode found = api.get("/api/v1/marketplace/products?q=led bulbs&localityId=" + kukatpally, null).data();
        JsonNode generic = null;
        for (JsonNode p : found.path("items")) if (p.path("code").asText().equals("LED_BULB_9W")) generic = p;
        assertThat(generic).as(found.toString()).isNotNull();
        assertThat(generic.path("shop_count").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(generic.path("min_price").decimalValue()).isEqualByComparingTo("89");
        // Typo-tolerant: "hamer" still finds the hammer.
        assertThat(texts(api.get("/api/v1/marketplace/products?q=hamer", null).data().path("items"), "code")).contains("HAMMER");

        JsonNode page = api.get("/api/v1/marketplace/products/" + productId("LED_BULB_9W") + "?localityId=" + kukatpally, null).data();
        JsonNode offers = page.path("offers");
        assertThat(texts(offers, "shop_name")).contains("Sri Sai Hardware", "Bright Spark Electricals");
        JsonNode cheapest = null;
        for (JsonNode o : offers) if (o.path("cheapest").asBoolean()) cheapest = o;
        assertThat(cheapest.path("shop_name").asText()).isEqualTo("Bright Spark Electricals");
        assertThat(offers.get(0).path("distance_km").isNumber()).isTrue();
        assertThat(page.path("stock_note").asText()).contains("declared by each shop");

        // Shops near Kukatpally, nearest first.
        JsonNode shops = api.get("/api/v1/marketplace/shops?localityId=" + kukatpally, null).data();
        assertThat(shops.get(0).path("display_name").asText()).isEqualTo("Sri Sai Hardware");

        // One shop per cart.
        String sriSaiBulb = listing("9000000026", "LED_BULB_9W_CDL");
        String sriSaiMcb = listing("9000000026", "MCB_16A");
        assertThat(api.post("/api/v1/customer/product-cart/items", customer, Map.of("listing_id", sriSaiBulb, "quantity", 6)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/customer/product-cart/items", customer, Map.of("listing_id", sriSaiMcb, "quantity", 2)).status()).isEqualTo(200);
        var otherShop = api.post("/api/v1/customer/product-cart/items", customer, Map.of("listing_id", listing("9000000024", "LED_BULB_9W_CDL"), "quantity", 1));
        assertThat(otherShop.status()).isEqualTo(409);
        assertThat(otherShop.errorCode()).isEqualTo("CART_VENDOR_MISMATCH");
        JsonNode cart = api.get("/api/v1/customer/product-cart?addressId=" + home, customer).data();
        assertThat(cart.path("shop").path("name").asText()).isEqualTo("Sri Sai Hardware");
        assertThat(cart.path("items_total").decimalValue()).isEqualByComparingTo("1058"); // 6 x 94 + 2 x 247
        assertThat(cart.path("delivery").path("available").asBoolean()).isTrue();
        assertThat(cart.path("delivery").path("fee").decimalValue()).isEqualByComparingTo("0"); // free above Rs 1,000

        // The shop changes a price after the customer added it: checkout refuses until the customer accepts the new price.
        String shop = api.login("9000000026", "PARTNER");
        Map<String, Object> bump = new LinkedHashMap<>(Map.of("variant_id", variant("LED_BULB_9W_CDL"), "price", 96, "mrp", 99, "stock_status", "IN_STOCK"));
        assertThat(api.post("/api/v1/partner/listings", shop, bump).status()).isEqualTo(200);
        try {
            Map<String, Object> checkout = Map.of("fulfilment", "DELIVERY", "address_id", home, "payment_method", "ONLINE");
            var changed = api.post("/api/v1/customer/orders", customer, checkout, ApiClient.key());
            assertThat(changed.status()).isEqualTo(409);
            assertThat(changed.errorCode()).isEqualTo("PRICE_CHANGED");
            assertThat(api.post("/api/v1/customer/product-cart/accept-prices", customer, Map.of()).data().path("price_changed").asBoolean()).isFalse();

            String key = ApiClient.key();
            var placed = api.post("/api/v1/customer/orders", customer, checkout, key);
            assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
            JsonNode order = placed.data().path("order");
            String orderId = order.path("id").asText();
            assertThat(order.path("status").asText()).isEqualTo("PAYMENT_PENDING");
            assertThat(order.path("total_amount").decimalValue()).isEqualByComparingTo("1070"); // 6 x 96 + 2 x 247, free delivery
            assertThat(order.path("prices_include_tax").asBoolean()).isTrue();
            assertThat(order.path("tax_total").decimalValue()).isPositive();
            assertThat(order.path("request_type").asText()).isEqualTo("PRODUCT_PURCHASE");
            assertThat(order.path("completion_code").asText()).hasSize(4);
            // Same Idempotency-Key -> the same order, not a second one; the cart was emptied.
            var again = api.post("/api/v1/customer/orders", customer, checkout, key);
            assertThat(again.data().path("duplicate").asBoolean()).isTrue();
            assertThat(again.data().path("order").path("id").asText()).isEqualTo(orderId);
            assertThat(api.get("/api/v1/customer/product-cart", customer).data().path("items").size()).isZero();

            // Shops never see unpaid online orders. Payment fails first: the order is kept and can be retried.
            assertThat(api.get("/api/v1/partner/orders/" + orderId, shop).status()).isEqualTo(404);
            simulate(customer, placed.data().path("payment").path("id").asText(), "FAILURE");
            assertThat(api.get("/api/v1/customer/orders/" + orderId, customer).data().path("status").asText()).isEqualTo("PAYMENT_PENDING");
            var retry = api.post("/api/v1/customer/orders/" + orderId + "/pay", customer, Map.of(), ApiClient.key());
            assertThat(retry.status()).as(retry.body().toString()).isEqualTo(200);
            simulate(customer, retry.data().path("id").asText(), "SUCCESS");
            assertThat(api.get("/api/v1/customer/orders/" + orderId, customer).data().path("status").asText()).isEqualTo("PAID");

            // The shop sees the paid order without the customer's code or phone until it confirms.
            JsonNode forShop = api.get("/api/v1/partner/orders/" + orderId, shop).data();
            assertThat(forShop.has("completion_code")).isFalse();
            assertThat(forShop.has("customer_phone")).isFalse();
            assertThat(forShop.path("fulfilment_snapshot").path("delivery_address").has("line1")).isFalse();
            assertThat(forShop.path("actions").toString()).contains("CONFIRM", "REJECT");
            assertThat(api.post("/api/v1/partner/orders/" + orderId + "/confirm", shop, Map.of()).data().path("customer_phone").asText()).isNotBlank();
            assertThat(api.post("/api/v1/partner/orders/" + orderId + "/ready", shop, Map.of()).status()).isEqualTo(400); // delivery order
            assertThat(api.post("/api/v1/partner/orders/" + orderId + "/dispatch", shop, Map.of()).data().path("status").asText()).isEqualTo("OUT_FOR_DELIVERY");
            // The customer can no longer cancel; another shop can't touch it.
            assertThat(api.post("/api/v1/customer/orders/" + orderId + "/cancel", customer, Map.of("reason", "Changed my mind")).status()).isEqualTo(409);
            String otherShopToken = api.login("9000000024", "PARTNER");
            assertThat(api.get("/api/v1/partner/orders/" + orderId, otherShopToken).status()).isEqualTo(404);
            assertThat(api.post("/api/v1/partner/orders/" + orderId + "/complete", otherShopToken, Map.of("code", "0000")).status()).isEqualTo(404);
            // Another customer can't see it either.
            assertThat(api.get("/api/v1/customer/orders/" + orderId, api.login(ApiClient.randomPhone(), "CUSTOMER")).status()).isEqualTo(404);

            // Hand-over: a wrong code is counted, the right code completes and settles the order.
            String code = api.get("/api/v1/customer/orders/" + orderId, customer).data().path("completion_code").asText();
            String wrong = code.equals("1111") ? "2222" : "1111";
            var bad = api.post("/api/v1/partner/orders/" + orderId + "/complete", shop, Map.of("code", wrong));
            assertThat(bad.status()).isEqualTo(400);
            assertThat(jdbc.sql("SELECT code_attempts FROM orders WHERE id = ?::uuid").param(orderId).query(Integer.class).single()).isEqualTo(1);
            assertThat(api.post("/api/v1/partner/orders/" + orderId + "/complete", shop, Map.of("code", code)).data().path("status").asText()).isEqualTo("COMPLETED");

            String requestId = order.path("request_id").asText();
            var hub = jdbc.sql("SELECT status, lifecycle, request_type FROM service_requests WHERE id = ?::uuid").param(requestId).query().singleRow();
            assertThat(hub).containsEntry("status", "COMPLETED").containsEntry("lifecycle", "ORDER").containsEntry("request_type", "PRODUCT_PURCHASE");
            assertThat(jdbc.sql("SELECT count(*) FROM ledger_entries WHERE reference_type = 'ORDER' AND reference_id = ?::uuid").param(orderId)
                    .query(Integer.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT status FROM partner_payouts WHERE request_id = ?::uuid").param(requestId).query(String.class).single()).isEqualTo("PENDING");
            List<String> timeline = jdbc.sql("SELECT to_status FROM request_status_history WHERE request_id = ?::uuid ORDER BY created_at, id")
                    .param(requestId).query(String.class).list();
            assertThat(timeline).containsSubsequence("PAYMENT_PENDING", "PAID", "VENDOR_CONFIRMED", "OUT_FOR_DELIVERY", "COMPLETED");
            // My Requests shows the order as done.
            JsonNode mine = api.get("/api/v1/customer/requests?type=PRODUCT_PURCHASE", customer).data();
            assertThat(mine.get(0).path("status_group").asText()).isEqualTo("DONE");
            assertThat(mine.get(0).path("order_number").asText()).startsWith("BZ-ORD-");

            // Immutable snapshots: order lines and money can't be changed, even directly in the database.
            assertThatThrownBy(() -> jdbc.sql("UPDATE order_items SET unit_price = 1 WHERE order_id = ?::uuid").param(orderId).update())
                    .hasMessageContaining("immutable");
            assertThatThrownBy(() -> jdbc.sql("UPDATE orders SET total_amount = 1 WHERE id = ?::uuid").param(orderId).update())
                    .hasMessageContaining("immutable");
        } finally {
            bump.put("price", 94);
            api.post("/api/v1/partner/listings", shop, bump);
        }
    }

    @Test
    void payAtPickupOrdersCanBeCancelledUntilTheShopConfirms() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        api.post("/api/v1/customer/product-cart/items", customer, Map.of("listing_id", listing("9000000028", "AASHIRVAAD_ATTA_5KG"), "quantity", 1));
        // Below the shop's minimum quantity: wholesale listing starts at its MOQ.
        String cement = listing("9000000027", "ULTRATECH_OPC_53");
        var replace = api.post("/api/v1/customer/product-cart/items", customer, Map.of("listing_id", cement, "quantity", 1, "replace_cart", true));
        assertThat(replace.status()).isEqualTo(200);
        assertThat(replace.data().path("shop").path("name").asText()).isEqualTo("Deccan Building Supplies");
        assertThat(replace.data().path("items").get(0).path("quantity").asInt()).isEqualTo(10);
        assertThat(api.put("/api/v1/customer/product-cart/items/" + cement, customer, Map.of("quantity", 5)).status()).isEqualTo(400);
        // Bulk tier applies from 50 bags.
        assertThat(api.put("/api/v1/customer/product-cart/items/" + cement, customer, Map.of("quantity", 50)).data().path("items").get(0)
                .path("unit_price").decimalValue()).isEqualByComparingTo("380");

        var placed = api.post("/api/v1/customer/orders", customer, Map.of("fulfilment", "PICKUP", "payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(placed.status()).as(placed.body().toString()).isEqualTo(200);
        String orderId = placed.data().path("order").path("id").asText();
        assertThat(placed.data().path("order").path("status").asText()).isEqualTo("PLACED");
        assertThat(placed.data().path("payment").path("status").asText()).isEqualTo("COLLECT_ON_DELIVERY");
        String wholesaler = api.login("9000000027", "PARTNER");
        assertThat(texts(api.get("/api/v1/partner/orders?group=NEW", wholesaler).data(), "id")).contains(orderId);

        var cancelled = api.post("/api/v1/customer/orders/" + orderId + "/cancel", customer, Map.of("reason", "Bought elsewhere"));
        assertThat(cancelled.data().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(api.post("/api/v1/partner/orders/" + orderId + "/confirm", wholesaler, Map.of()).status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT status FROM payments WHERE order_id = ?::uuid").param(orderId).query(String.class).single()).isEqualTo("FAILED");
    }

    // ----- B + C + M: quotation request to several shops, one accepted ------------------------------------------

    private String hardware() {
        return jdbc.sql("SELECT id::text FROM categories WHERE code = 'HARDWARE_SUPPLIES'").query(String.class).single();
    }

    private String plumbingSub() {
        return jdbc.sql("""
                SELECT s.id::text FROM subcategories s JOIN categories c ON c.id = s.category_id WHERE c.code = 'HARDWARE_SUPPLIES' AND s.code = 'PLUMBING_SUPPLIES'""")
                .query(String.class).single();
    }

    private JsonNode createRfq(String customer, String addressId, boolean bulk) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category_id", hardware());
        body.put("subcategory_id", plumbingSub());
        body.put("address_id", addressId);
        body.put("fulfilment", "DELIVERY");
        body.put("bulk", bulk);
        body.put("description", "Bathroom renovation - need everything by Friday");
        body.put("items", List.of(
                Map.of("name", "uPVC pipe 1/2 inch, 3 m", "brand", "Supreme", "specs", "SCH 40, ISI marked", "quantity", 40, "unit", "lengths"),
                Map.of("name", "PVC elbow 1/2 inch", "quantity", 100, "unit", "pcs"),
                Map.of("name", "Teflon tape", "quantity", 10, "notes", "Any brand")));
        var res = api.post("/api/v1/customer/rfqs", customer, body, ApiClient.key());
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.data();
    }

    private String invitation(String partnerToken, String requestId) {
        for (JsonNode l : api.get("/api/v1/partner/leads", partnerToken).data()) {
            if (l.path("request_id").asText().equals(requestId)) return l.path("invitation_id").asText();
        }
        throw new AssertionError("No lead for " + requestId);
    }

    @Test
    void quotationRequestGoesToSeveralShopsWhoQuoteItemWiseAndTheCustomerAcceptsOne() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String addr = address(customer, "Miyapur");
        JsonNode rfq = createRfq(customer, addr, false);
        String requestId = rfq.path("id").asText();
        assertThat(rfq.path("status").asText()).isEqualTo("PARTNER_QUOTATION");
        assertThat(jdbc.sql("SELECT count(*) FROM request_items WHERE request_id = ?::uuid").param(requestId).query(Integer.class).single()).isEqualTo(3);
        List<String> invited = jdbc.sql("""
                SELECT p.display_name FROM partner_invitations i JOIN partners p ON p.id = i.partner_id WHERE i.request_id = ?::uuid ORDER BY 1""")
                .param(requestId).query(String.class).list();
        assertThat(invited).contains("Deccan Building Supplies", "Sri Sai Hardware").doesNotContain("FreshMart Supermarket", "Bright Spark Electricals");

        // C: shops see the requested brand / specs and quote item by item (brand, discount, tax, MOQ, lead time, terms).
        String deccan = api.login("9000000027", "PARTNER");
        String deccanInv = invitation(deccan, requestId);
        JsonNode lead = api.get("/api/v1/partner/leads/" + deccanInv, deccan).data();
        assertThat(lead.path("request_type").asText()).isEqualTo("PRODUCT_RFQ");
        assertThat(lead.path("line_items").get(0).path("brand_preference").asText()).isEqualTo("Supreme");
        assertThat(lead.path("line_items").get(0).path("specs").asText()).isEqualTo("SCH 40, ISI marked");
        List<Map<String, Object>> deccanLines = new ArrayList<>();
        for (JsonNode l : lead.path("line_items")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.path("key").asText());
            m.put("available", true);
            m.put("unit_price", l.path("quantity").asInt() == 40 ? 140 : l.path("quantity").asInt() == 100 ? 11 : 20);
            m.put("brand", l.path("quantity").asInt() == 40 ? "Supreme" : "Generic");
            m.put("discount_percent", 5);
            m.put("tax_percent", 18);
            m.put("moq", 10);
            m.put("lead_time_days", 1);
            deccanLines.add(m);
        }
        var q1 = api.post("/api/v1/partner/leads/" + deccanInv + "/item-quote", deccan, Map.of("lines", deccanLines, "delivery_fee", 300,
                "terms", "GST invoice. Unloading by customer.", "exclusions", "No returns on cut pipes", "lead_time_days", 1, "pay_on_delivery_allowed", true));
        assertThat(q1.status()).as(q1.body().toString()).isEqualTo(200);
        // (40 x 140 + 100 x 11 + 10 x 20) = 6900; -5% = 6555; +18% GST = 7734.90; + delivery 300 = 8034.90
        assertThat(q1.data().path("total_amount").decimalValue()).isEqualByComparingTo("8034.90");

        String sriSai = api.login("9000000026", "PARTNER");
        String sriInv = invitation(sriSai, requestId);
        List<Map<String, Object>> sriLines = new ArrayList<>();
        for (JsonNode l : api.get("/api/v1/partner/leads/" + sriInv, sriSai).data().path("line_items")) {
            boolean elbow = l.path("quantity").asInt() == 100;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.path("key").asText());
            m.put("available", !elbow);
            if (!elbow) m.put("unit_price", l.path("quantity").asInt() == 40 ? 150 : 19);
            sriLines.add(m);
        }
        var q2 = api.post("/api/v1/partner/leads/" + sriInv + "/item-quote", sriSai, Map.of("lines", sriLines, "delivery_fee", 0));
        assertThat(q2.status()).as(q2.body().toString()).isEqualTo(200);

        // M: an uninvited shop can't open the lead.
        String freshMart = api.login("9000000028", "PARTNER");
        assertThat(api.get("/api/v1/partner/leads/" + deccanInv, freshMart).status()).isEqualTo(404);

        // B: side-by-side comparison.
        JsonNode cmp = api.get("/api/v1/customer/requests/" + requestId + "/compare", customer).data();
        assertThat(cmp.path("items").size()).isEqualTo(3);
        assertThat(cmp.path("quotes").size()).isEqualTo(2);
        String deccanQuote = q1.data().path("id").asText();
        assertThat(cmp.path("best").path("lowest_complete").asText()).isEqualTo(deccanQuote);
        assertThat(cmp.path("best").path("most_items").asText()).isEqualTo(deccanQuote);
        assertThat(api.get("/api/v1/customer/requests/" + requestId + "/compare", api.login(ApiClient.randomPhone(), "CUSTOMER")).status()).isEqualTo(404);

        // Accept ONE shop: the request becomes an order with that shop; the other quote is closed.
        var accept = api.post("/api/v1/customer/requests/" + requestId + "/quotations/" + deccanQuote + "/accept", customer,
                Map.of("payment_method", "ONLINE", "fulfilment", "DELIVERY"), ApiClient.key());
        assertThat(accept.status()).as(accept.body().toString()).isEqualTo(200);
        JsonNode order = accept.data().path("order");
        assertThat(order.path("source").asText()).isEqualTo("RFQ");
        assertThat(order.path("status").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(order.path("total_amount").decimalValue()).isEqualByComparingTo("8034.90");
        assertThat(order.path("items").size()).isEqualTo(3);
        assertThat(order.path("fulfilment_snapshot").path("quote_terms").asText()).contains("GST invoice");
        assertThat(jdbc.sql("SELECT status FROM quotations WHERE id = ?::uuid").param(q2.data().path("id").asText()).query(String.class).single())
                .isEqualTo("REJECTED");
        var second = api.post("/api/v1/customer/requests/" + requestId + "/quotations/" + q2.data().path("id").asText() + "/accept", customer,
                Map.of("payment_method", "PAY_ON_DELIVERY"), ApiClient.key());
        assertThat(second.status()).isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM orders WHERE request_id = ?::uuid").param(requestId).query(Integer.class).single()).isEqualTo(1);

        simulate(customer, accept.data().path("payment").path("id").asText(), "SUCCESS");
        String orderId = order.path("id").asText();
        assertThat(texts(api.get("/api/v1/partner/orders?group=NEW", deccan).data(), "id")).contains(orderId);
        assertThat(api.get("/api/v1/partner/orders/" + orderId, sriSai).status()).isEqualTo(404);
        JsonNode detail = api.get("/api/v1/customer/requests/" + requestId, customer).data();
        assertThat(detail.path("order").path("status").asText()).isEqualTo("PAID");
        assertThat(detail.path("structured_rfq").asBoolean()).isTrue();
    }

    @Test
    void bulkRequestsGoToWholesalersFirstAndInviteCountsComeFromAdminRules() {
        String admin = api.login("9000000001", "ADMIN");
        var rule = api.post("/api/v1/admin/marketplace/rfq-rules", admin, Map.of("category_id", hardware(), "invite_count", 1));
        assertThat(rule.status()).as(rule.body().toString()).isEqualTo(200);
        String ruleId = null;
        for (JsonNode r : rule.data()) if (r.path("category_id").asText().equals(hardware())) ruleId = r.path("id").asText();
        try {
            String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
            String requestId = createRfq(customer, address(customer, "Kukatpally"), true).path("id").asText();
            List<String> invited = jdbc.sql("""
                    SELECT p.display_name FROM partner_invitations i JOIN partners p ON p.id = i.partner_id WHERE i.request_id = ?::uuid""")
                    .param(requestId).query(String.class).list();
            assertThat(invited).containsExactly("Deccan Building Supplies");
            // Only admins can change the rules.
            assertThat(api.post("/api/v1/admin/marketplace/rfq-rules", api.login("9000000026", "PARTNER"), Map.of("invite_count", 3)).status())
                    .isEqualTo(403);
        } finally {
            if (ruleId != null) api.call("DELETE", "/api/v1/admin/marketplace/rfq-rules/" + ruleId, admin, null, null);
        }
    }

    // ----- I: a new shop is approved by an admin before customers can buy from it ------------------------------------

    @Test
    void newShopIsInvisibleUntilAdminApprovesTheShopItsCategoryAndItsListing() {
        String admin = api.login("9000000001", "ADMIN");
        String sub = jdbc.sql("SELECT id::text FROM subcategories WHERE code = 'HOME_APPLIANCES'").query(String.class).single();
        var product = api.post("/api/v1/admin/marketplace/products", admin, Map.of("subcategory_id", sub, "name", "Bajaj Mixer Grinder 750 W " + ApiClient.key().substring(0, 6),
                "brand", "Bajaj", "unit", "each", "tax_rate_percent", 18, "specs", Map.of("Power", "750 W", "Jars", "3")));
        assertThat(product.status()).as(product.body().toString()).isEqualTo(200);
        String productId = product.data().path("id").asText();
        String variantId = jdbc.sql("SELECT id::text FROM product_variants WHERE product_id = ?::uuid").param(productId).query(String.class).single();

        // A new shop registers (self-service) with a location.
        String phone = ApiClient.randomPhone();
        String token = api.loginOnly(phone, "PARTNER");
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String terms = api.get("/api/v1/partner/terms", token).data().path("id").asText();
        Map<String, Object> reg = new LinkedHashMap<>();
        reg.put("partner_type", "BUSINESS");
        reg.put("display_name", "Lakshmi Appliances " + phone.substring(6));
        reg.put("business_name", "Lakshmi Appliances Pvt Ltd");
        reg.put("city_id", city);
        reg.put("address", "Shop 9, Ameerpet");
        reg.put("subcategory_ids", List.of(sub));
        reg.put("locality_ids", List.of(locality("Ameerpet")));
        reg.put("terms_version_id", terms);
        var registered = api.post("/api/v1/partner/registration", token, reg);
        assertThat(registered.status()).as(registered.body().toString()).isEqualTo(200);
        String partnerId = registered.data().path("partner_id").asText();
        String shop = api.loginOnly(phone, "PARTNER");
        JsonNode me = api.get("/api/v1/partner/shop", shop).data();
        assertThat(me.path("is_shop").asBoolean()).isFalse();
        assertThat(me.path("roles").get(0).path("role").asText()).isEqualTo("RETAIL_SHOP");
        assertThat(me.path("roles").get(0).path("status").asText()).isEqualTo("REQUESTED");
        assertThat(me.path("locations").get(0).path("locality_name").asText()).isEqualTo("Ameerpet");

        // Can't list before the category is approved.
        Map<String, Object> listingBody = Map.of("variant_id", variantId, "price", 3299, "mrp", 3999, "stock_status", "IN_STOCK");
        assertThat(api.post("/api/v1/partner/listings", shop, listingBody).errorCode()).isEqualTo("PARTNER_NOT_ELIGIBLE");
        // Only admins approve.
        assertThat(api.post("/api/v1/admin/partners/" + partnerId + "/registration", shop, Map.of("decision", "APPROVED")).status()).isEqualTo(403);

        assertThat(api.post("/api/v1/admin/partners/" + partnerId + "/registration", admin, Map.of("decision", "APPROVED")).status()).isEqualTo(200);
        api.post("/api/v1/partner/kyc", shop, Map.of("doc_type", "GSTIN", "doc_number", "36ABCDE1234F1Z5"));
        assertThat(api.post("/api/v1/admin/partners/" + partnerId + "/kyc", admin, Map.of("decision", "VERIFIED")).status()).isEqualTo(200);
        String capability = jdbc.sql("SELECT id::text FROM partner_capabilities WHERE partner_id = ?::uuid").param(partnerId).query(String.class).single();
        assertThat(api.post("/api/v1/admin/partner-capabilities/" + capability, admin, Map.of("decision", "APPROVED")).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/partner/shop", shop).data().path("is_shop").asBoolean()).isTrue();

        // The listing waits for review; customers don't see it yet.
        JsonNode listing = api.post("/api/v1/partner/listings", shop, listingBody).data();
        assertThat(listing.path("status").asText()).isEqualTo("PENDING");
        assertThat(api.get("/api/v1/marketplace/products/" + productId, null).data().path("offers").size()).isZero();
        JsonNode pending = api.get("/api/v1/admin/marketplace/listings?status=PENDING", admin).data();
        assertThat(texts(pending, "id")).contains(listing.path("id").asText());
        assertThat(texts(api.get("/api/v1/admin/marketplace/shops?roleStatus=APPROVED", admin).data(), "id")).contains(partnerId);

        assertThat(api.post("/api/v1/admin/marketplace/listings/" + listing.path("id").asText() + "/review", admin, Map.of("decision", "ACTIVE")).status())
                .isEqualTo(200);
        JsonNode offers = api.get("/api/v1/marketplace/products/" + productId, null).data().path("offers");
        assertThat(texts(offers, "partner_id")).containsExactly(partnerId);

        // A shop-type role suspended by admin hides the shop again (data kept).
        assertThat(api.post("/api/v1/admin/marketplace/shops/" + partnerId + "/roles/RETAIL_SHOP", admin, Map.of("decision", "SUSPENDED", "note", "Test")).status())
                .isEqualTo(200);
        assertThat(api.get("/api/v1/marketplace/products/" + productId, null).data().path("offers").size()).isZero();
        assertThat(jdbc.sql("SELECT status FROM vendor_listings WHERE id = ?::uuid").param(listing.path("id").asText()).query(String.class).single())
                .isEqualTo("ACTIVE");

        // A product that isn't in the catalogue yet: proposed by the shop, live once admin approves it.
        api.post("/api/v1/admin/marketplace/shops/" + partnerId + "/roles/RETAIL_SHOP", admin, Map.of("decision", "APPROVED"));
        var proposal = api.post("/api/v1/partner/products", shop, Map.of("subcategory_id", sub, "name", "Prestige Induction Cooktop 1600 W",
                "brand", "Prestige", "price", 2499, "specs", Map.of("Power", "1600 W")));
        assertThat(proposal.status()).as(proposal.body().toString()).isEqualTo(200);
        String proposed = proposal.data().path("product_id").asText();
        assertThat(api.get("/api/v1/marketplace/products/" + proposed, null).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/admin/marketplace/products/" + proposed + "/review", admin, Map.of("decision", "APPROVED")).status()).isEqualTo(200);
        assertThat(texts(api.get("/api/v1/marketplace/products/" + proposed, null).data().path("offers"), "partner_id")).containsExactly(partnerId);
    }
}
