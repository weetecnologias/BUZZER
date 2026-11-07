package com.buzzer.catalogue;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import com.buzzer.identity.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin management of the dynamic catalogue. Categories, subcategories, services, forms and
 * availability are all data: adding a new category or service needs no code deployment.
 * Items are deactivated, never hard-deleted, so historic requests keep their references.
 */
@RestController
@RequestMapping("/api/v1/admin/catalogue")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminCatalogueController {
    private static final String CODE = "^[A-Z0-9_]{2,80}$";
    private static final Set<String> WORKFLOWS = Set.of("MENU_DRIVEN", "PROPERTY_REQUIREMENT", "CATEGORY_SPECIAL_REQUEST",
            "GLOBAL_SPECIAL_REQUEST", "JOB_RECRUITMENT", "EVENT_BRIEF", "API_PRICED");
    private static final Set<String> PRICING = Set.of("FIXED", "STARTING_FROM", "RANGE", "PER_UNIT", "HOURLY",
            "VISIT_PLUS_LABOUR", "PACKAGE", "QUOTE_REQUIRED", "API_PRICED");

    public record CategoryInput(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 160) String name,
                                @Size(max = 2000) String description, @Size(max = 80) String icon, @Size(max = 500) String imageUrl,
                                Integer sortOrder, Boolean active, String workflowTemplate, Boolean isGlobalSpecial,
                                @Size(max = 2000) String advisoryNotice, JsonNode defaultFormSchema, List<UUID> cityIds) {}

    public record SubcategoryInput(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 160) String name,
                                   @Size(max = 2000) String description, @Size(max = 80) String icon, Integer sortOrder, Boolean active) {}

    public record ServiceInput(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank @Size(max = 160) String name,
                               @Size(max = 2000) String description, @Size(max = 80) String icon, String pricingType,
                               @DecimalMin("0") BigDecimal basePrice, @DecimalMin("0") BigDecimal minPrice,
                               @DecimalMin("0") BigDecimal maxPrice, @Size(max = 40) String unit, Boolean isOther,
                               Boolean payOnDeliveryAllowed, JsonNode formSchema, Integer sortOrder, Boolean active,
                               @Size(max = 500) String imageUrl, @Size(max = 80) String menuGroup) {}

    public record ReorderInput(@NotEmpty List<UUID> orderedIds) {}

    private final JdbcClient jdbc;
    private final Json json;
    private final FormSchemaValidator forms;
    private final AuditService audit;

    public AdminCatalogueController(JdbcClient jdbc, Json json, FormSchemaValidator forms, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.forms = forms;
        this.audit = audit;
    }

    // ----- Read (includes inactive items) ---------------------------------------------------------

    @GetMapping("/tree")
    public ApiResponse<List<Map<String, Object>>> tree() {
        List<Map<String, Object>> cats = jdbc.sql("""
                SELECT c.id, c.code, c.name, c.description, c.icon, c.image_url, c.sort_order, c.active, c.workflow_template,
                       c.is_global_special, c.advisory_notice, c.default_form_schema::text AS default_form_schema,
                       (SELECT count(*) FROM subcategories s WHERE s.category_id = c.id) AS subcategory_count,
                       (SELECT count(*) FROM service_items i JOIN subcategories s ON s.id = i.subcategory_id WHERE s.category_id = c.id) AS service_count,
                       (SELECT count(DISTINCT pc.partner_id) FROM partner_capabilities pc JOIN subcategories s ON s.id = pc.subcategory_id
                          WHERE s.category_id = c.id AND pc.status = 'APPROVED') AS approved_partner_count
                FROM categories c ORDER BY c.sort_order, c.name""").query().listOfRows();
        return ApiResponse.of(cats);
    }

    @GetMapping("/categories/{id}/subcategories")
    public ApiResponse<List<Map<String, Object>>> subcategories(@PathVariable UUID id) {
        return ApiResponse.of(jdbc.sql("""
                SELECT s.id, s.code, s.name, s.description, s.icon, s.sort_order, s.active,
                  (SELECT count(*) FROM service_items i WHERE i.subcategory_id = s.id) AS service_count,
                  (SELECT count(DISTINCT pc.partner_id) FROM partner_capabilities pc WHERE pc.subcategory_id = s.id AND pc.status = 'APPROVED') AS approved_partner_count
                FROM subcategories s WHERE s.category_id = :c ORDER BY s.sort_order, s.name""").param("c", id).query().listOfRows());
    }

    @GetMapping("/subcategories/{id}/services")
    public ApiResponse<List<Map<String, Object>>> services(@PathVariable UUID id) {
        return ApiResponse.of(jdbc.sql("""
                SELECT id, code, name, description, icon, image_url, menu_group, pricing_type, base_price, min_price, max_price, unit, is_other,
                       pay_on_delivery_allowed, form_schema::text AS form_schema, sort_order, active
                FROM service_items WHERE subcategory_id = :s ORDER BY is_other, sort_order, name""").param("s", id).query().listOfRows());
    }

    // ----- Categories ---------------------------------------------------------------------------

    @PostMapping("/categories")
    @Transactional
    public ApiResponse<Map<String, Object>> createCategory(@Valid @RequestBody CategoryInput in) {
        validateCategory(in);
        UUID id = jdbc.sql("""
                INSERT INTO categories(code, name, description, icon, image_url, sort_order, active, workflow_template,
                                       is_global_special, advisory_notice, default_form_schema)
                VALUES (:code, :name, :d, :icon, :img, COALESCE(:so, (SELECT COALESCE(max(sort_order),0)+1 FROM categories)),
                        COALESCE(:active, TRUE), COALESCE(:wf, 'MENU_DRIVEN'), COALESCE(:gs, FALSE), :adv, CAST(:form AS jsonb))
                RETURNING id""")
                .param("code", in.code()).param("name", in.name()).param("d", in.description()).param("icon", in.icon())
                .param("img", in.imageUrl()).param("so", in.sortOrder()).param("active", in.active())
                .param("wf", in.workflowTemplate()).param("gs", in.isGlobalSpecial()).param("adv", in.advisoryNotice())
                .param("form", json.write(in.defaultFormSchema())).query(UUID.class).single();
        setCities(id, in.cityIds());
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "CATEGORY_CREATED", "CATEGORY", id, Map.of("code", in.code()));
        return ApiResponse.of(Map.of("id", id));
    }

    @PutMapping("/categories/{id}")
    @Transactional
    public ApiResponse<Map<String, Object>> updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryInput in) {
        validateCategory(in);
        int n = jdbc.sql("""
                UPDATE categories SET code = :code, name = :name, description = :d, icon = :icon, image_url = :img,
                  sort_order = COALESCE(:so, sort_order), active = COALESCE(:active, active),
                  workflow_template = COALESCE(:wf, workflow_template), is_global_special = COALESCE(:gs, is_global_special),
                  advisory_notice = :adv, default_form_schema = CAST(:form AS jsonb), updated_at = now()
                WHERE id = :id""")
                .param("code", in.code()).param("name", in.name()).param("d", in.description()).param("icon", in.icon())
                .param("img", in.imageUrl()).param("so", in.sortOrder()).param("active", in.active())
                .param("wf", in.workflowTemplate()).param("gs", in.isGlobalSpecial()).param("adv", in.advisoryNotice())
                .param("form", json.write(in.defaultFormSchema())).param("id", id).update();
        if (n == 0) throw BusinessException.notFound("Category");
        if (in.cityIds() != null) setCities(id, in.cityIds());
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "CATEGORY_UPDATED", "CATEGORY", id, Map.of("code", in.code(), "active", String.valueOf(in.active())));
        return ApiResponse.of(Map.of("id", id));
    }

    @PostMapping("/categories/reorder")
    @Transactional
    public ApiResponse<Map<String, Object>> reorderCategories(@Valid @RequestBody ReorderInput in) {
        reorder("categories", in.orderedIds(), null, null);
        return ApiResponse.of(Map.of("reordered", in.orderedIds().size()));
    }

    // ----- Subcategories ------------------------------------------------------------------------

    @PostMapping("/categories/{categoryId}/subcategories")
    @Transactional
    public ApiResponse<Map<String, Object>> createSubcategory(@PathVariable UUID categoryId, @Valid @RequestBody SubcategoryInput in) {
        UUID id = jdbc.sql("""
                INSERT INTO subcategories(category_id, code, name, description, icon, sort_order, active)
                VALUES (:c, :code, :name, :d, :icon,
                        COALESCE(:so, (SELECT COALESCE(max(sort_order),0)+1 FROM subcategories WHERE category_id = :c)), COALESCE(:a, TRUE))
                RETURNING id""")
                .param("c", categoryId).param("code", in.code()).param("name", in.name()).param("d", in.description())
                .param("icon", in.icon()).param("so", in.sortOrder()).param("a", in.active()).query(UUID.class).single();
        // Every subcategory gets its own "Other ... Requirement" so special requests stay in context.
        jdbc.sql("""
                INSERT INTO service_items(subcategory_id, code, name, pricing_type, is_other, sort_order)
                VALUES (:s, :code, :name, 'QUOTE_REQUIRED', TRUE, 999)""")
                .param("s", id).param("code", "OTHER_" + in.code()).param("name", "Other " + in.name() + " Requirement").update();
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "SUBCATEGORY_CREATED", "SUBCATEGORY", id, Map.of("code", in.code()));
        return ApiResponse.of(Map.of("id", id));
    }

    @PutMapping("/subcategories/{id}")
    public ApiResponse<Map<String, Object>> updateSubcategory(@PathVariable UUID id, @Valid @RequestBody SubcategoryInput in) {
        int n = jdbc.sql("""
                UPDATE subcategories SET code = :code, name = :name, description = :d, icon = :icon,
                  sort_order = COALESCE(:so, sort_order), active = COALESCE(:a, active), updated_at = now() WHERE id = :id""")
                .param("code", in.code()).param("name", in.name()).param("d", in.description()).param("icon", in.icon())
                .param("so", in.sortOrder()).param("a", in.active()).param("id", id).update();
        if (n == 0) throw BusinessException.notFound("Subcategory");
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "SUBCATEGORY_UPDATED", "SUBCATEGORY", id, Map.of("code", in.code()));
        return ApiResponse.of(Map.of("id", id));
    }

    @PostMapping("/categories/{categoryId}/subcategories/reorder")
    @Transactional
    public ApiResponse<Map<String, Object>> reorderSubcategories(@PathVariable UUID categoryId, @Valid @RequestBody ReorderInput in) {
        reorder("subcategories", in.orderedIds(), "category_id", categoryId);
        return ApiResponse.of(Map.of("reordered", in.orderedIds().size()));
    }

    // ----- Services -----------------------------------------------------------------------------

    @PostMapping("/subcategories/{subcategoryId}/services")
    public ApiResponse<Map<String, Object>> createService(@PathVariable UUID subcategoryId, @Valid @RequestBody ServiceInput in) {
        validateService(in);
        UUID id = jdbc.sql("""
                INSERT INTO service_items(subcategory_id, code, name, description, icon, pricing_type, base_price, min_price,
                  max_price, unit, is_other, pay_on_delivery_allowed, form_schema, sort_order, active, image_url, menu_group)
                VALUES (:s, :code, :name, :d, :icon, COALESCE(:pt, 'QUOTE_REQUIRED'), :bp, :minp, :maxp, :unit,
                  COALESCE(:other, FALSE), COALESCE(:pod, TRUE), CAST(:form AS jsonb),
                  COALESCE(:so, (SELECT COALESCE(max(sort_order),0)+1 FROM service_items WHERE subcategory_id = :s AND NOT is_other)),
                  COALESCE(:a, TRUE), :img, :grp)
                RETURNING id""")
                .param("img", in.imageUrl()).param("grp", blankToNull(in.menuGroup())).param("s", subcategoryId).param("code", in.code()).param("name", in.name()).param("d", in.description())
                .param("icon", in.icon()).param("pt", in.pricingType()).param("bp", in.basePrice()).param("minp", in.minPrice())
                .param("maxp", in.maxPrice()).param("unit", in.unit()).param("other", in.isOther())
                .param("pod", in.payOnDeliveryAllowed()).param("form", json.write(in.formSchema())).param("so", in.sortOrder())
                .param("a", in.active()).query(UUID.class).single();
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "SERVICE_CREATED", "SERVICE", id, Map.of("code", in.code()));
        return ApiResponse.of(Map.of("id", id));
    }

    @PutMapping("/services/{id}")
    public ApiResponse<Map<String, Object>> updateService(@PathVariable UUID id, @Valid @RequestBody ServiceInput in) {
        validateService(in);
        int n = jdbc.sql("""
                UPDATE service_items SET code = :code, name = :name, description = :d, icon = :icon,
                  pricing_type = COALESCE(:pt, pricing_type), base_price = :bp, min_price = :minp, max_price = :maxp, unit = :unit,
                  is_other = COALESCE(:other, is_other), pay_on_delivery_allowed = COALESCE(:pod, pay_on_delivery_allowed),
                  form_schema = CAST(:form AS jsonb), sort_order = COALESCE(:so, sort_order), active = COALESCE(:a, active),
                  image_url = :img, menu_group = :grp, updated_at = now() WHERE id = :id""")
                .param("img", in.imageUrl()).param("grp", blankToNull(in.menuGroup()))
                .param("code", in.code()).param("name", in.name()).param("d", in.description()).param("icon", in.icon())
                .param("pt", in.pricingType()).param("bp", in.basePrice()).param("minp", in.minPrice()).param("maxp", in.maxPrice())
                .param("unit", in.unit()).param("other", in.isOther()).param("pod", in.payOnDeliveryAllowed())
                .param("form", json.write(in.formSchema())).param("so", in.sortOrder()).param("a", in.active()).param("id", id).update();
        if (n == 0) throw BusinessException.notFound("Service");
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "SERVICE_UPDATED", "SERVICE", id, Map.of("code", in.code()));
        return ApiResponse.of(Map.of("id", id));
    }

    // ----- helpers ------------------------------------------------------------------------------

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private void validateCategory(CategoryInput in) {
        if (in.workflowTemplate() != null && !WORKFLOWS.contains(in.workflowTemplate())) {
            throw BusinessException.invalid("Unknown workflow template " + in.workflowTemplate());
        }
        forms.validateSchema(in.defaultFormSchema());
    }

    private void validateService(ServiceInput in) {
        if (in.pricingType() != null && !PRICING.contains(in.pricingType())) {
            throw BusinessException.invalid("Unknown pricing type " + in.pricingType());
        }
        if (in.minPrice() != null && in.maxPrice() != null && in.minPrice().compareTo(in.maxPrice()) > 0) {
            throw BusinessException.invalid("Minimum price cannot exceed maximum price");
        }
        forms.validateSchema(in.formSchema());
    }

    private void setCities(UUID categoryId, List<UUID> cityIds) {
        if (cityIds == null) {
            jdbc.sql("INSERT INTO category_cities(category_id, city_id) SELECT :c, id FROM cities ON CONFLICT DO NOTHING")
                    .param("c", categoryId).update();
            return;
        }
        jdbc.sql("UPDATE category_cities SET active = FALSE WHERE category_id = :c").param("c", categoryId).update();
        for (UUID city : cityIds) {
            jdbc.sql("""
                    INSERT INTO category_cities(category_id, city_id, active) VALUES (:c, :city, TRUE)
                    ON CONFLICT (category_id, city_id) DO UPDATE SET active = TRUE""")
                    .param("c", categoryId).param("city", city).update();
        }
    }

    private void reorder(String table, List<UUID> ids, String parentColumn, UUID parentId) {
        int i = 1;
        for (UUID id : ids) {
            String sql = "UPDATE " + table + " SET sort_order = :o WHERE id = :id" + (parentColumn == null ? "" : " AND " + parentColumn + " = :p");
            var stmt = jdbc.sql(sql).param("o", i++).param("id", id);
            if (parentColumn != null) stmt = stmt.param("p", parentId);
            stmt.update();
        }
    }
}
