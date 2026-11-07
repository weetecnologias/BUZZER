package com.buzzer;

import com.buzzer.common.ExcelSupport;
import com.buzzer.finance.CommissionService;
import com.buzzer.support.ApiClient;
import com.buzzer.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/** Excel catalogue/commission import, media upload, exports, partner price lists, instant quotes and transfers. */
@AutoConfigureMockMvc
class AdminToolsTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired CommissionService commissions;
    ApiClient api;
    String admin;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc, mapper);
        admin = api.login("9000000001", "ADMIN");
    }

    private JsonNode upload(String url, String token, byte[] bytes, String name, String type) throws Exception {
        MvcResult r = mvc.perform(multipart(url).file(new MockMultipartFile("file", name, type, bytes))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private byte[] download(String url, String token) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getContentAsByteArray();
    }

    private static byte[] sheet(String name, List<String> headers, List<List<Object>> rows) {
        return ExcelSupport.write(List.of(new ExcelSupport.SheetData(name, headers, rows)));
    }

    @Test
    void mediaUploadAcceptsRealImagesOnly() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};
        JsonNode ok = upload("/api/v1/admin/media", admin, png, "fan.png", "image/png");
        String url = ok.path("data").path("url").asText();
        assertThat(url).startsWith("/api/v1/media/");
        MvcResult img = mvc.perform(get(url)).andReturn();
        assertThat(img.getResponse().getContentType()).isEqualTo("image/png");
        JsonNode bad = upload("/api/v1/admin/media", admin, "<script>alert(1)</script>".getBytes(), "x.png", "image/png");
        assertThat(bad.path("error").path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void catalogueExcelPreviewsErrorsAndImportsAllOrNothing() throws Exception {
        byte[] template = download("/api/v1/admin/catalogue/excel/template?prefill=true", admin);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertThat(wb.getSheet("Catalogue").getLastRowNum()).isGreaterThan(200);
            assertThat(wb.getSheet("Instructions")).isNotNull();
        }
        String code = "PETS" + (System.nanoTime() % 100000);
        List<String> h = com.buzzer.catalogue.CatalogueExcelService.COLUMNS;
        List<Object> good = new ArrayList<>(List.of(code, "Pet Care", "MENU_DRIVEN", "paw", "", "GROOMING", "Pet Grooming", "", "Dogs",
                "DOG_BATH", "Dog Bath & Brush", "Shampoo, dry, brush", "FIXED", 699, "", "", "per pet", "45 min", "", "Y", "Y", 1));
        List<Object> bad = new ArrayList<>(List.of(code, "Pet Care", "", "", "", "GROOMING", "Pet Grooming", "", "Cats",
                "CAT_BATH", "Cat Bath", "", "FIXED", "", "", "", "", "", "ftp://bad", "maybe", "", ""));
        byte[] withError = sheet("Catalogue", h, List.of(good, bad));
        JsonNode preview = upload("/api/v1/admin/catalogue/excel/preview", admin, withError, "c.xlsx", "application/octet-stream");
        assertThat(preview.path("data").path("error_rows").asInt()).isEqualTo(1);
        assertThat(preview.path("data").path("rows").get(1).path("errors").toString()).contains("base_price", "Image URL", "pay_on_delivery");
        JsonNode rejected = upload("/api/v1/admin/catalogue/excel/import", admin, withError, "c.xlsx", "application/octet-stream");
        assertThat(rejected.path("error").path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM categories WHERE code = ?").param(code).query(Integer.class).single()).isZero();

        JsonNode imported = upload("/api/v1/admin/catalogue/excel/import", admin, sheet("Catalogue", h, List.of(good)), "c.xlsx", "application/octet-stream");
        assertThat(imported.path("data").path("imported").asBoolean()).isTrue();
        var row = jdbc.sql("""
                SELECT i.name, i.menu_group, i.base_price::text AS price FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id
                JOIN categories c ON c.id = s.category_id WHERE c.code = ? AND i.code = 'DOG_BATH'""").param(code).query().singleRow();
        assertThat(row.get("menu_group")).isEqualTo("Dogs");
        assertThat(row.get("price")).isEqualTo("699.00");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id JOIN categories c ON c.id = s.category_id
                WHERE c.code = ? AND i.is_other""").param(code).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void commissionExcelCreatesDatedRules() throws Exception {
        byte[] template = download("/api/v1/admin/finance/commission/excel", admin);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            Sheet s = wb.getSheet("Commission");
            Row pest = null;
            for (Row r : s) if ("SUBCATEGORY".equals(r.getCell(0).getStringCellValue()) && "PEST_CONTROL".equals(r.getCell(2).getStringCellValue())) pest = r;
            assertThat(pest).isNotNull();
            pest.createCell(6).setCellValue(7.5);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            JsonNode res = upload("/api/v1/admin/finance/commission/excel/import", admin, out.toByteArray(), "rates.xlsx", "application/octet-stream");
            assertThat(res.path("data").path("changes").asInt()).isEqualTo(1);
        }
        UUID pestSub = jdbc.sql("SELECT id FROM subcategories WHERE code = 'PEST_CONTROL'").query(UUID.class).single();
        UUID cat = jdbc.sql("SELECT category_id FROM subcategories WHERE code = 'PEST_CONTROL'").query(UUID.class).single();
        assertThat(commissions.resolve(null, null, pestSub, cat, null).ratePercent()).isEqualByComparingTo("7.50");

        JsonNode bad = upload("/api/v1/admin/finance/commission/excel/preview", admin,
                sheet("Commission", List.of("scope_type", "category_code", "subcategory_code", "item_code", "name", "current_rate_percent", "new_rate_percent", "effective_from"),
                        List.of(List.of("CATEGORY", "NOPE", "", "", "", "", 150, ""))), "r.xlsx", "application/octet-stream");
        assertThat(bad.path("data").path("error_rows").asInt()).isEqualTo(1);
    }

    @Test
    void anyTableExportsToExcel() throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/export/xlsx")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("title", "customers", "columns", List.of(Map.of("key", "name", "label", "Name")),
                        "rows", List.of(Map.of("name", "=HYPERLINK(\"x\")"), Map.of("name", "Lokesh")))))).andReturn();
        assertThat(r.getResponse().getContentType()).contains("spreadsheetml");
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).startsWith("'="); // formula injection neutralised
        }
    }

    @Test
    void partnerPriceListApprovalThenInstantQuoteForCustomerCart() {
        String partner = api.login("9000000021", "PARTNER");
        JsonNode list = api.get("/api/v1/partner/price-list", partner).data();
        String tap = null, fan = jdbc.sql("""
                SELECT i.id::text FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.code = 'ELECTRICAL' AND i.code = 'FAN_REPAIR'""")
                .query(String.class).single();
        for (JsonNode n : list) if (n.path("name").asText().equals("Tap Repair")) tap = n.path("service_item_id").asText();
        assertThat(tap).isNotNull();
        // A plumber cannot price electrical work.
        assertThat(api.put("/api/v1/partner/price-list", partner, Map.of("prices", List.of(Map.of("service_item_id", fan, "price", 100)))).errorCode())
                .isEqualTo("PARTNER_NOT_ELIGIBLE");
        api.put("/api/v1/partner/price-list", partner, Map.of("prices", List.of(Map.of("service_item_id", tap, "price", 180))));

        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String city = jdbc.sql("SELECT id::text FROM cities WHERE code = 'HYD'").query(String.class).single();
        String address = api.post("/api/v1/customer/addresses", customer, Map.of("label", "HOME", "line1", "Plot 4, Green Valley",
                "city_id", city, "pincode", "500032")).data().path("id").asText();
        api.post("/api/v1/customer/cart/items", customer, Map.of("service_item_id", tap, "quantity", 2));
        String requestId = api.post("/api/v1/customer/cart/checkout", customer, Map.of("mode", "SELF", "address_id", address), ApiClient.key())
                .data().get(0).path("id").asText();
        String agentPhone = jdbc.sql("""
                SELECT substring(u.phone from 4) FROM service_requests r JOIN agents a ON a.id = r.agent_id JOIN users u ON u.id = a.user_id WHERE r.id = ?::uuid""")
                .param(requestId).query(String.class).single();
        String agent = api.login(agentPhone, "AGENT");
        String partnerId = jdbc.sql("SELECT p.id::text FROM partners p JOIN users u ON u.id = p.user_id WHERE u.phone = '+919000000021'").query(String.class).single();

        // Pending price is not usable yet.
        JsonNode options = api.get("/api/v1/agent/requests/" + requestId + "/instant-quotes", agent).data();
        JsonNode mine = null;
        for (JsonNode o : options) if (o.path("partner_id").asText().equals(partnerId)) mine = o;
        assertThat(mine).isNotNull();
        assertThat(mine.path("complete").asBoolean()).isFalse();

        String priceId = jdbc.sql("SELECT pp.id::text FROM partner_price_items pp WHERE pp.partner_id = ?::uuid AND pp.service_item_id = ?::uuid")
                .param(partnerId).param(tap).query(String.class).single();
        assertThat(api.post("/api/v1/admin/price-lists/review", admin, Map.of("ids", List.of(priceId), "approve", true)).status()).isEqualTo(200);

        var created = api.post("/api/v1/agent/requests/" + requestId + "/instant-quotes", agent, Map.of("partner_ids", List.of(partnerId)));
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.data().get(0).path("total_amount").decimalValue()).isEqualByComparingTo("360");
        var q = jdbc.sql("SELECT source, total_amount::text AS total FROM quotations WHERE request_id = ?::uuid").param(requestId).query().singleRow();
        assertThat(q.get("source")).isEqualTo("PRICE_LIST");
        assertThat(jdbc.sql("SELECT status FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single()).isEqualTo("PARTNER_QUOTATION");
    }

    @Test
    void requestAndExceptionTransfersToAnotherAgent() {
        String customer = api.login(ApiClient.randomPhone(), "CUSTOMER");
        String requestId = api.post("/api/v1/customer/agent-request", customer, Map.of("message", "Need someone to renew my vehicle insurance"), ApiClient.key())
                .data().path("id").asText();
        String current = jdbc.sql("SELECT agent_id::text FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single();
        String target = jdbc.sql("SELECT a.id::text FROM agents a WHERE a.active AND a.id <> ?::uuid AND a.is_manager ORDER BY a.created_at LIMIT 1")
                .param(current).query(String.class).single();
        jdbc.sql("""
                INSERT INTO ops_exceptions(exception_type, priority, title, ref_type, ref_id) VALUES ('SLA_BREACH', 'HIGH', 'Test breach', 'REQUEST', ?::uuid)""")
                .param(requestId).update();
        String exceptionId = jdbc.sql("SELECT id::text FROM ops_exceptions WHERE ref_id = ?::uuid").param(requestId).query(String.class).single();
        String targetUser = jdbc.sql("SELECT user_id::text FROM agents WHERE id = ?::uuid").param(target).query(String.class).single();

        assertThat(api.get("/api/v1/admin/staff", admin).data().size()).isGreaterThan(3);
        var r = api.post("/api/v1/admin/exceptions/" + exceptionId + "/assign", admin,
                Map.of("owner_user_id", targetUser, "note", "Manager to handle", "transfer_request_to_agent_id", target));
        assertThat(r.status()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT agent_id::text FROM service_requests WHERE id = ?::uuid").param(requestId).query(String.class).single()).isEqualTo(target);
        assertThat(jdbc.sql("SELECT owner_user_id::text FROM ops_exceptions WHERE id = ?::uuid").param(exceptionId).query(String.class).single()).isEqualTo(targetUser);
        assertThat(jdbc.sql("SELECT count(*) FROM request_status_history WHERE request_id = ?::uuid AND reason LIKE 'Transferred to%'")
                .param(requestId).query(Integer.class).single()).isEqualTo(1);
    }
}
