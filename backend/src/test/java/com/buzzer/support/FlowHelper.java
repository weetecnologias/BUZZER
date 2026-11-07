package com.buzzer.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Drives a request to CUSTOMER_APPROVAL with N shared quotations, for tests that start mid-flow. */
public class FlowHelper {
    public record Prepared(String customerPhone, String customerToken, String requestId, String agentToken, List<String> quotationIds) {}

    private final ApiClient api;
    private final JdbcClient jdbc;

    public FlowHelper(ApiClient api, JdbcClient jdbc) {
        this.api = api;
        this.jdbc = jdbc;
    }

    public Prepared toCustomerApproval(int quotes, boolean podAllowed) {
        String phone = ApiClient.randomPhone();
        String customer = api.login(phone, "CUSTOMER");
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String address = api.post("/api/v1/customer/addresses", customer, Map.of("label", "HOME", "line1", "House 12, Road 3, Madhapur",
                "city_id", city, "pincode", "500081")).data().path("id").asText();
        String tap = jdbc.sql("SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code='PLUMBING' AND i.code='TAP_REPAIR'")
                .query(String.class).single();
        String requestId = api.post("/api/v1/customer/requests", customer, Map.of("service_item_id", tap, "address_id", address,
                "answers", Map.of("problem_area", "Bathroom", "leak_severity", "Slow drip", "details", "Bathroom tap keeps dripping all night")))
                .data().path("id").asText();
        String agentPhone = jdbc.sql("""
                SELECT substring(u.phone from 4) FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id JOIN users u ON u.id = a.user_id
                JOIN users c ON c.id = l.customer_user_id WHERE c.phone = ? AND l.link_role = 'PRIMARY' AND l.active""")
                .param("+91" + phone).query(String.class).single();
        String agent = api.login(agentPhone, "AGENT");
        api.post("/api/v1/agent/requests/" + requestId + "/acknowledge", agent, Map.of());
        String[] partnerPhones = {"9000000021", "9000000022", "9000000023"};
        List<String> partnerIds = new ArrayList<>();
        for (int i = 0; i < quotes; i++) {
            partnerIds.add(jdbc.sql("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = ?")
                    .param("+91" + partnerPhones[i]).query(String.class).single());
        }
        var inv = api.post("/api/v1/agent/requests/" + requestId + "/invitations", agent, Map.of("partner_ids", partnerIds));
        if (inv.status() != 200) throw new IllegalStateException(inv.body().toString());
        List<String> quotationIds = new ArrayList<>();
        for (int i = 0; i < quotes; i++) {
            String partner = api.login(partnerPhones[i], "PARTNER");
            String invitationId = null;
            for (JsonNode l : api.get("/api/v1/partner/leads?status=INVITED", partner).data()) {
                if (l.path("request_id").asText().equals(requestId)) invitationId = l.path("invitation_id").asText();
            }
            var q = api.post("/api/v1/partner/leads/" + invitationId + "/quote", partner, Map.of("scope_of_work", "Replace tap cartridge",
                    "labour_amount", 200 + i * 50, "materials_amount", 150, "visit_fee", 0, "pay_on_delivery_allowed", podAllowed));
            if (q.status() != 200) throw new IllegalStateException(q.body().toString());
            quotationIds.add(q.data().path("id").asText());
        }
        var share = api.post("/api/v1/agent/requests/" + requestId + "/share-quotations", agent, Map.of("quotation_ids", quotationIds));
        if (share.status() != 200) throw new IllegalStateException(share.body().toString());
        return new Prepared(phone, customer, requestId, agent, quotationIds);
    }
}
