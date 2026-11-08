package com.buzzer.marketplace;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.common.ExcelSupport;
import com.buzzer.common.Json;
import com.buzzer.common.SettingsService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shop side of the marketplace: partner types, shop locations (radius, hours, pickup / delivery), product listings with
 * price, stock status and minimum order, proposing new products, and bulk Excel upload. A shop can only list products in
 * subcategories it holds an approved capability for; new listings and proposed products go through admin review.
 */
@Service
public class PartnerShopService {
    public static final Set<String> ROLES = Set.of("RETAIL_SHOP", "WHOLESALER", "DISTRIBUTOR", "SERVICE_PROVIDER", "TASK_PARTNER");
    public static final Set<String> SHOP_ROLES = Set.of("RETAIL_SHOP", "WHOLESALER", "DISTRIBUTOR");
    private static final Set<String> STOCK = Set.of("IN_STOCK", "LIMITED", "OUT_OF_STOCK", "ON_REQUEST");
    private static final BigDecimal MAX_PRICE = new BigDecimal("10000000");

    public record LocationInput(String name, String address, UUID localityId, BigDecimal latitude, BigDecimal longitude,
                                BigDecimal serviceRadiusKm, String openingHours, String phone, Boolean pickupEnabled, Boolean deliveryEnabled,
                                BigDecimal deliveryFee, BigDecimal freeDeliveryAbove, BigDecimal minOrderValue, Boolean primary, Boolean active) {}

    public record ListingInput(UUID variantId, BigDecimal price, BigDecimal mrp, String stockStatus, Integer moq, Integer maxOrderQty,
                               BigDecimal bulkPrice, Integer bulkMinQty, Integer leadTimeDays, String notes, Boolean paused) {}

    public record ProductProposal(UUID subcategoryId, String name, String brand, String description, String unit, Map<String, String> specs,
                                  BigDecimal taxRatePercent, BigDecimal price, BigDecimal mrp, String stockStatus, Integer moq) {}

    private final JdbcClient jdbc;
    private final SettingsService settings;
    private final AuditService audit;
    private final Json json;

    public PartnerShopService(JdbcClient jdbc, SettingsService settings, AuditService audit, Json json) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
        this.json = json;
    }

    // ----- Roles ----------------------------------------------------------------------------------------

    public List<Map<String, Object>> roles(UUID partnerId) {
        return jdbc.sql("SELECT role, status, review_note, reviewed_at, created_at FROM partner_roles WHERE partner_id = :p ORDER BY role")
                .param("p", partnerId).query().listOfRows();
    }

    /** Partner asks for an additional partner type. Shop types need admin approval (approving the registration approves them too). */
    @Transactional
    public void requestRole(UUID partnerId, String role, UUID actor) {
        if (role == null || !ROLES.contains(role)) throw BusinessException.invalid("Unknown partner type");
        jdbc.sql("""
                INSERT INTO partner_roles(partner_id, role, status) VALUES (:p, :r, 'REQUESTED')
                ON CONFLICT (partner_id, role) DO UPDATE SET status = CASE WHEN partner_roles.status = 'REJECTED' THEN 'REQUESTED'
                                                                           ELSE partner_roles.status END""")
                .param("p", partnerId).param("r", role).update();
        audit.record(actor, "PARTNER", "PARTNER_ROLE_REQUESTED", "PARTNER", partnerId, Map.of("role", role));
    }

    /** True when the partner holds an approved shop-type role. */
    public boolean isShop(UUID partnerId) {
        return jdbc.sql("SELECT count(*) FROM partner_roles WHERE partner_id = :p AND status = 'APPROVED' AND role IN " + MarketplaceSql.SHOP_ROLES)
                .param("p", partnerId).query(Integer.class).single() > 0;
    }

    // ----- Locations ------------------------------------------------------------------------------------

    public List<Map<String, Object>> locations(UUID partnerId) {
        return jdbc.sql("""
                SELECT pl.id, pl.name, pl.address, pl.city_id, c.name AS city_name, pl.locality_id, l.name AS locality_name, pl.latitude,
                       pl.longitude, pl.service_radius_km, pl.opening_hours, pl.phone, pl.pickup_enabled, pl.delivery_enabled, pl.delivery_fee,
                       pl.free_delivery_above, pl.min_order_value, pl.is_primary, pl.active, pl.updated_at
                FROM partner_locations pl JOIN cities c ON c.id = pl.city_id LEFT JOIN localities l ON l.id = pl.locality_id
                WHERE pl.partner_id = :p ORDER BY pl.active DESC, pl.is_primary DESC, pl.name""").param("p", partnerId).query().listOfRows();
    }

    @Transactional
    public Map<String, Object> saveLocation(UUID partnerId, UUID locationId, LocationInput in, UUID actor) {
        if (in == null) throw BusinessException.invalid("Location details are required");
        String name = trim(in.name());
        if (name == null || name.length() < 2 || name.length() > 120) throw BusinessException.invalid("Location name must be 2-120 characters");
        if (in.address() != null && in.address().length() > 400) throw BusinessException.invalid("Address is too long");
        if (in.openingHours() != null && in.openingHours().length() > 200) throw BusinessException.invalid("Opening hours text is too long");
        if ((in.latitude() == null) != (in.longitude() == null)) throw BusinessException.invalid("Give both latitude and longitude, or neither");
        if (in.latitude() != null && (in.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0 || in.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw BusinessException.invalid("Invalid coordinates");
        }
        BigDecimal radius = in.serviceRadiusKm() == null ? BigDecimal.TEN : in.serviceRadiusKm();
        if (radius.signum() <= 0 || radius.compareTo(BigDecimal.valueOf(200)) > 0) throw BusinessException.invalid("Service radius must be 1-200 km");
        BigDecimal fee = money(in.deliveryFee(), "Delivery fee");
        BigDecimal minOrder = money(in.minOrderValue(), "Minimum order value");
        BigDecimal freeAbove = in.freeDeliveryAbove() == null ? null : money(in.freeDeliveryAbove(), "Free delivery amount");
        boolean pickup = in.pickupEnabled() == null || in.pickupEnabled();
        boolean delivery = Boolean.TRUE.equals(in.deliveryEnabled());
        if (!pickup && !delivery) throw BusinessException.invalid("Offer pickup, delivery or both");
        UUID cityId = jdbc.sql("SELECT city_id FROM partners WHERE id = :p").param("p", partnerId).query(UUID.class).single();
        BigDecimal lat = in.latitude();
        BigDecimal lng = in.longitude();
        if (in.localityId() != null) {
            record Loc(UUID cityId, BigDecimal latitude, BigDecimal longitude) {}
            Loc loc = jdbc.sql("SELECT city_id, latitude, longitude FROM localities WHERE id = :l").param("l", in.localityId())
                    .query(Loc.class).optional().orElseThrow(() -> BusinessException.invalid("Unknown locality"));
            if (!loc.cityId().equals(cityId)) throw BusinessException.invalid("The location must be in your registered city");
            if (lat == null) {
                lat = loc.latitude();
                lng = loc.longitude();
            }
        }
        boolean active = in.active() == null || in.active();
        UUID id;
        if (locationId == null) {
            boolean first = jdbc.sql("SELECT count(*) FROM partner_locations WHERE partner_id = :p AND active").param("p", partnerId)
                    .query(Integer.class).single() == 0;
            boolean primary = first || Boolean.TRUE.equals(in.primary());
            if (primary) jdbc.sql("UPDATE partner_locations SET is_primary = FALSE WHERE partner_id = :p").param("p", partnerId).update();
            id = jdbc.sql("""
                    INSERT INTO partner_locations(partner_id, name, address, city_id, locality_id, latitude, longitude, service_radius_km,
                      opening_hours, phone, pickup_enabled, delivery_enabled, delivery_fee, free_delivery_above, min_order_value, is_primary, active)
                    VALUES (:p, :n, :a, :c, :l, :lat, :lng, :r, :h, :ph, :pu, :de, :fee, :free, :min, :prim, TRUE) RETURNING id""")
                    .param("p", partnerId).param("n", name).param("a", trim(in.address())).param("c", cityId).param("l", in.localityId())
                    .param("lat", lat).param("lng", lng).param("r", radius).param("h", trim(in.openingHours())).param("ph", trim(in.phone()))
                    .param("pu", pickup).param("de", delivery).param("fee", fee).param("free", freeAbove).param("min", minOrder)
                    .param("prim", primary).query(UUID.class).single();
        } else {
            Boolean wasPrimary = jdbc.sql("SELECT is_primary FROM partner_locations WHERE id = :id AND partner_id = :p")
                    .param("id", locationId).param("p", partnerId).query(Boolean.class).optional()
                    .orElseThrow(() -> BusinessException.notFound("Location"));
            boolean primary = Boolean.TRUE.equals(in.primary()) || (wasPrimary && in.primary() == null);
            if (!active && wasPrimary) throw BusinessException.invalid("Make another location primary before closing this one");
            if (primary) jdbc.sql("UPDATE partner_locations SET is_primary = FALSE WHERE partner_id = :p AND id <> :id")
                    .param("p", partnerId).param("id", locationId).update();
            jdbc.sql("""
                    UPDATE partner_locations SET name = :n, address = :a, locality_id = :l, latitude = :lat, longitude = :lng, service_radius_km = :r,
                      opening_hours = :h, phone = :ph, pickup_enabled = :pu, delivery_enabled = :de, delivery_fee = :fee, free_delivery_above = :free,
                      min_order_value = :min, is_primary = :prim, active = :act, updated_at = now()
                    WHERE id = :id AND partner_id = :p""")
                    .param("n", name).param("a", trim(in.address())).param("l", in.localityId()).param("lat", lat).param("lng", lng)
                    .param("r", radius).param("h", trim(in.openingHours())).param("ph", trim(in.phone())).param("pu", pickup).param("de", delivery)
                    .param("fee", fee).param("free", freeAbove).param("min", minOrder).param("prim", primary).param("act", active)
                    .param("id", locationId).param("p", partnerId).update();
            id = locationId;
        }
        audit.record(actor, "PARTNER", locationId == null ? "SHOP_LOCATION_ADDED" : "SHOP_LOCATION_UPDATED", "PARTNER", partnerId,
                Map.of("location", id.toString()));
        return locations(partnerId).stream().filter(l -> id.equals(l.get("id"))).findFirst().orElseThrow();
    }

    // ----- Catalogue and listings -----------------------------------------------------------------------------

    /** Catalogue products the shop may list (approved subcategories), with the shop's own listing if any. */
    public List<Map<String, Object>> catalogue(UUID partnerId, String q, UUID subcategoryId) {
        String query = MarketplaceService.blankToNull(q);
        return jdbc.sql("""
                SELECT pr.id AS product_id, pr.code, pr.name, pr.unit, pr.status AS product_status, b.name AS brand, s.id AS subcategory_id,
                       s.name AS subcategory_name, c.name AS category_name, v.id AS variant_id, v.name AS variant_name, v.sku,
                       l.id AS listing_id, l.price, l.mrp, l.stock_status, l.moq, l.status AS listing_status
                FROM products pr JOIN product_variants v ON v.product_id = pr.id AND v.active
                JOIN subcategories s ON s.id = pr.subcategory_id JOIN categories c ON c.id = s.category_id
                LEFT JOIN brands b ON b.id = pr.brand_id
                LEFT JOIN vendor_listings l ON l.variant_id = v.id AND l.partner_id = :p
                WHERE (pr.status = 'ACTIVE' OR (pr.status = 'PENDING' AND pr.proposed_by_partner_id = :p))
                  AND EXISTS (SELECT 1 FROM partner_capabilities pc WHERE pc.partner_id = :p AND pc.status = 'APPROVED' AND pc.subcategory_id = pr.subcategory_id)
                  AND (CAST(:sub AS uuid) IS NULL OR s.id = CAST(:sub AS uuid))
                  AND (CAST(:q AS text) IS NULL OR pr.name ILIKE '%' || CAST(:q AS text) || '%' OR b.name ILIKE '%' || CAST(:q AS text) || '%'
                       OR v.sku ILIKE '%' || CAST(:q AS text) || '%')
                ORDER BY c.sort_order, s.sort_order, pr.sort_order, pr.name, v.sort_order LIMIT 500""")
                .param("p", partnerId).param("sub", subcategoryId).param("q", query).query().listOfRows();
    }

    public List<Map<String, Object>> listings(UUID partnerId, String status) {
        return jdbc.sql("""
                SELECT l.id, l.variant_id, v.name AS variant_name, v.sku, pr.id AS product_id, pr.name AS product_name, pr.unit, b.name AS brand,
                       s.name AS subcategory_name, l.price, l.mrp, l.stock_status, l.stock_updated_at, l.moq, l.max_order_qty, l.bulk_price,
                       l.bulk_min_qty, l.lead_time_days, l.notes, l.status, l.review_note, l.updated_at, pr.status AS product_status
                FROM vendor_listings l JOIN product_variants v ON v.id = l.variant_id JOIN products pr ON pr.id = v.product_id
                JOIN subcategories s ON s.id = pr.subcategory_id LEFT JOIN brands b ON b.id = pr.brand_id
                WHERE l.partner_id = :p AND (CAST(:s AS text) IS NULL OR l.status = CAST(:s AS text))
                ORDER BY l.status = 'ACTIVE' DESC, pr.name, v.sort_order""").param("p", partnerId).param("s", MarketplaceService.blankToNull(status))
                .query().listOfRows();
    }

    /**
     * Creates or updates the shop's listing for a variant. Price and stock edits on a live listing apply at once; a new
     * listing waits for admin review (unless auto-approval is switched on). Re-submitting a rejected listing reopens review.
     */
    @Transactional
    public Map<String, Object> saveListing(UUID partnerId, ListingInput in, UUID actor) {
        if (in == null || in.variantId() == null) throw BusinessException.invalid("Choose a product");
        record Variant(UUID id, UUID subcategoryId, String productStatus, UUID proposedBy, String name) {}
        Variant v = jdbc.sql("""
                SELECT v.id, pr.subcategory_id, pr.status AS product_status, pr.proposed_by_partner_id AS proposed_by, pr.name
                FROM product_variants v JOIN products pr ON pr.id = v.product_id WHERE v.id = :v AND v.active""")
                .param("v", in.variantId()).query(Variant.class).optional().orElseThrow(() -> BusinessException.notFound("Product"));
        boolean ownPending = "PENDING".equals(v.productStatus()) && partnerId.equals(v.proposedBy());
        if (!"ACTIVE".equals(v.productStatus()) && !ownPending) throw BusinessException.invalid("This product is not available for listing");
        Integer capable = jdbc.sql("SELECT count(*) FROM partner_capabilities WHERE partner_id = :p AND status = 'APPROVED' AND subcategory_id = :s")
                .param("p", partnerId).param("s", v.subcategoryId()).query(Integer.class).single();
        if (capable == 0) {
            throw new BusinessException(ErrorCode.PARTNER_NOT_ELIGIBLE, "Your shop is not approved for this category yet - request the category first");
        }
        BigDecimal price = price(in.price(), "Price");
        BigDecimal mrp = in.mrp() == null ? null : price(in.mrp(), "MRP");
        if (mrp != null && mrp.compareTo(price) < 0) throw BusinessException.invalid("MRP can't be lower than your price");
        String stock = in.stockStatus() == null ? "IN_STOCK" : in.stockStatus().toUpperCase();
        if (!STOCK.contains(stock)) throw BusinessException.invalid("Stock status must be IN_STOCK, LIMITED, OUT_OF_STOCK or ON_REQUEST");
        int moq = in.moq() == null ? 1 : in.moq();
        if (moq < 1 || moq > 100000) throw BusinessException.invalid("Minimum order quantity must be 1-100000");
        if (in.maxOrderQty() != null && in.maxOrderQty() < moq) throw BusinessException.invalid("Maximum order quantity can't be below the minimum");
        if ((in.bulkPrice() == null) != (in.bulkMinQty() == null)) throw BusinessException.invalid("Give both the bulk price and the quantity it starts from");
        BigDecimal bulk = in.bulkPrice() == null ? null : price(in.bulkPrice(), "Bulk price");
        if (bulk != null && (bulk.compareTo(price) >= 0 || in.bulkMinQty() < Math.max(2, moq))) {
            throw BusinessException.invalid("Bulk price must be lower than the normal price and start at 2 or more units");
        }
        int lead = in.leadTimeDays() == null ? 0 : in.leadTimeDays();
        if (lead < 0 || lead > 90) throw BusinessException.invalid("Lead time must be 0-90 days");
        String notes = trim(in.notes());
        if (notes != null && notes.length() > 300) throw BusinessException.invalid("Notes must be 300 characters or less");

        record Existing(UUID id, String status) {}
        Existing ex = jdbc.sql("SELECT id, status FROM vendor_listings WHERE partner_id = :p AND variant_id = :v FOR UPDATE")
                .param("p", partnerId).param("v", in.variantId()).query(Existing.class).optional().orElse(null);
        boolean autoApprove = settings.getBoolean("marketplace.listing_auto_approve", false) && "ACTIVE".equals(v.productStatus());
        String status;
        if (ex == null || "REJECTED".equals(ex.status())) status = autoApprove ? "ACTIVE" : "PENDING";
        else if ("PENDING".equals(ex.status())) status = "PENDING";
        else status = Boolean.TRUE.equals(in.paused()) ? "PAUSED" : Boolean.FALSE.equals(in.paused()) ? "ACTIVE" : ex.status();
        // An admin-paused listing can't be switched back on by the shop.
        if (ex != null && "PAUSED".equals(ex.status()) && "ACTIVE".equals(status)) {
            String note = jdbc.sql("SELECT review_note FROM vendor_listings WHERE id = :id").param("id", ex.id()).query(String.class).optional().orElse(null);
            if (note != null && note.startsWith("Admin:")) throw BusinessException.forbidden("This listing was paused by BUZZER: " + note.substring(6).trim());
        }
        UUID id = jdbc.sql("""
                INSERT INTO vendor_listings(partner_id, variant_id, price, mrp, stock_status, stock_updated_at, moq, max_order_qty, bulk_price,
                  bulk_min_qty, lead_time_days, notes, status, reviewed_at)
                VALUES (:p, :v, :price, :mrp, :stock, now(), :moq, :max, :bulk, :bmin, :lead, :notes, :st, CASE WHEN :st = 'ACTIVE' THEN now() END)
                ON CONFLICT (partner_id, variant_id) DO UPDATE SET price = EXCLUDED.price, mrp = EXCLUDED.mrp, stock_status = EXCLUDED.stock_status,
                  stock_updated_at = now(), moq = EXCLUDED.moq, max_order_qty = EXCLUDED.max_order_qty, bulk_price = EXCLUDED.bulk_price,
                  bulk_min_qty = EXCLUDED.bulk_min_qty, lead_time_days = EXCLUDED.lead_time_days, notes = EXCLUDED.notes, status = EXCLUDED.status,
                  review_note = CASE WHEN EXCLUDED.status = 'PENDING' THEN NULL ELSE vendor_listings.review_note END, updated_at = now()
                RETURNING id""")
                .param("p", partnerId).param("v", in.variantId()).param("price", price).param("mrp", mrp).param("stock", stock).param("moq", moq)
                .param("max", in.maxOrderQty()).param("bulk", bulk).param("bmin", in.bulkMinQty()).param("lead", lead).param("notes", notes)
                .param("st", status).query(UUID.class).single();
        audit.record(actor, "PARTNER", ex == null ? "LISTING_CREATED" : "LISTING_UPDATED", "LISTING", id,
                Map.of("price", price.toPlainString(), "stock", stock, "status", status, "product", v.name()));
        return listings(partnerId, null).stream().filter(l -> id.equals(l.get("id"))).findFirst().orElseThrow();
    }

    /** A product that is not in the catalogue yet: created PENDING with the shop's listing; admin approves both together. */
    @Transactional
    public Map<String, Object> proposeProduct(UUID partnerId, ProductProposal in, UUID actor) {
        if (in == null || in.subcategoryId() == null) throw BusinessException.invalid("Choose a subcategory");
        String name = trim(in.name());
        if (name == null || name.length() < 3 || name.length() > 200) throw BusinessException.invalid("Product name must be 3-200 characters");
        Integer capable = jdbc.sql("SELECT count(*) FROM partner_capabilities WHERE partner_id = :p AND status = 'APPROVED' AND subcategory_id = :s")
                .param("p", partnerId).param("s", in.subcategoryId()).query(Integer.class).single();
        if (capable == 0) throw new BusinessException(ErrorCode.PARTNER_NOT_ELIGIBLE, "Your shop is not approved for this subcategory");
        BigDecimal tax = in.taxRatePercent() == null ? BigDecimal.valueOf(18) : in.taxRatePercent();
        if (tax.signum() < 0 || tax.compareTo(BigDecimal.valueOf(40)) > 0) throw BusinessException.invalid("Tax rate must be 0-40%");
        if (in.description() != null && in.description().length() > 2000) throw BusinessException.invalid("Description is too long");
        Map<String, String> specs = in.specs() == null ? Map.of() : in.specs();
        if (specs.size() > 20) throw BusinessException.invalid("At most 20 specifications");
        UUID brandId = brand(in.brand());
        String code = "P_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        UUID productId = jdbc.sql("""
                INSERT INTO products(code, subcategory_id, brand_id, name, description, unit, specs, tax_rate_percent, status, proposed_by_partner_id)
                VALUES (:code, :s, :b, :n, :d, :u, CAST(:specs AS jsonb), :tax, 'PENDING', :p) RETURNING id""")
                .param("code", code).param("s", in.subcategoryId()).param("b", brandId).param("n", name).param("d", trim(in.description()))
                .param("u", in.unit() == null || in.unit().isBlank() ? "each" : in.unit().trim()).param("specs", json.write(specs))
                .param("tax", tax).param("p", partnerId).query(UUID.class).single();
        UUID variantId = jdbc.sql("INSERT INTO product_variants(product_id, name, sku) VALUES (:p, 'Standard', :sku) RETURNING id")
                .param("p", productId).param("sku", code).query(UUID.class).single();
        audit.record(actor, "PARTNER", "PRODUCT_PROPOSED", "PRODUCT", productId, Map.of("name", name));
        Map<String, Object> listing = saveListing(partnerId, new ListingInput(variantId, in.price(), in.mrp(), in.stockStatus(), in.moq(),
                null, null, null, null, null, null), actor);
        return Map.of("product_id", productId, "variant_id", variantId, "status", "PENDING", "listing", listing);
    }

    UUID brand(String name) {
        String n = trim(name);
        if (n == null) return null;
        if (n.length() > 120) throw BusinessException.invalid("Brand name is too long");
        return jdbc.sql("""
                INSERT INTO brands(name) VALUES (:n) ON CONFLICT (lower(name)) DO UPDATE SET name = brands.name RETURNING id""")
                .param("n", n).query(UUID.class).single();
    }

    // ----- Excel ------------------------------------------------------------------------------------------

    private static final List<String> HEADERS = List.of("sku", "product", "variant", "unit", "price", "mrp", "stock_status", "moq",
            "bulk_price", "bulk_min_qty", "lead_time_days", "notes", "listing_status");

    /** Every product the shop may list, with its current values. Fill in a price to list a product; clear nothing to keep it. */
    public byte[] excelTemplate(UUID partnerId) {
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : catalogue(partnerId, null, null)) {
            Map<String, Object> l = r.get("listing_id") == null ? Map.of() : jdbc.sql("""
                    SELECT bulk_price, bulk_min_qty, lead_time_days, notes FROM vendor_listings WHERE id = :id""")
                    .param("id", r.get("listing_id")).query().singleRow();
            List<Object> row = new ArrayList<>();
            row.add(r.get("sku"));
            row.add(r.get("name"));
            row.add(r.get("variant_name"));
            row.add(r.get("unit"));
            row.add(r.get("price"));
            row.add(r.get("mrp"));
            row.add(r.get("stock_status"));
            row.add(r.get("moq"));
            row.add(l.get("bulk_price"));
            row.add(l.get("bulk_min_qty"));
            row.add(l.get("lead_time_days"));
            row.add(l.get("notes"));
            row.add(r.get("listing_status"));
            rows.add(row);
        }
        return ExcelSupport.write(List.of(
                new ExcelSupport.SheetData("Listings", HEADERS, rows),
                new ExcelSupport.SheetData("Instructions", List.of("How to use"), List.of(
                        List.of("Fill in 'price' (and optionally mrp, stock_status, moq, bulk price) for every product you sell, then upload."),
                        List.of("stock_status: IN_STOCK, LIMITED, OUT_OF_STOCK or ON_REQUEST. Leave the price empty to skip a row."),
                        List.of("New listings are reviewed by BUZZER before customers see them. Price and stock changes apply at once."),
                        List.of("Do not change the sku column. Products you can't find: propose them in the app.")))));
    }

    @Transactional
    public Map<String, Object> excelUpload(UUID partnerId, MultipartFile file, UUID actor) {
        List<Map<String, String>> rows = ExcelSupport.read(file, "Listings");
        int saved = 0;
        int skipped = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String sku = row.getOrDefault("sku", "").trim();
            String priceText = row.getOrDefault("price", "").trim();
            if (priceText.isEmpty()) {
                skipped++;
                continue;
            }
            try {
                UUID variantId = jdbc.sql("SELECT id FROM product_variants WHERE sku = :s AND active").param("s", sku).query(UUID.class).optional()
                        .orElseThrow(() -> BusinessException.invalid("Unknown sku '" + sku + "'"));
                BigDecimal price = ExcelSupport.decimal(priceText);
                if (price == null) throw BusinessException.invalid("Price '" + priceText + "' is not a number");
                saveListing(partnerId, new ListingInput(variantId, price, ExcelSupport.decimal(row.get("mrp")),
                        blank(row.get("stock_status")), integer(row.get("moq")), null, ExcelSupport.decimal(row.get("bulk_price")),
                        integer(row.get("bulk_min_qty")), integer(row.get("lead_time_days")), blank(row.get("notes")), null), actor);
                saved++;
            } catch (BusinessException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("row", row.get("_row"));
                err.put("sku", sku);
                err.put("error", e.getMessage());
                errors.add(err);
            }
        }
        audit.record(actor, "PARTNER", "LISTINGS_EXCEL_IMPORTED", "PARTNER", partnerId, Map.of("saved", saved, "errors", errors.size()));
        return Map.of("saved", saved, "skipped", skipped, "errors", errors);
    }

    // ----- helpers ----------------------------------------------------------------------------------------------

    private static Integer integer(String v) {
        BigDecimal d = ExcelSupport.decimal(v);
        if (d == null) return null;
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            throw BusinessException.invalid("'" + v + "' must be a whole number");
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static BigDecimal price(BigDecimal v, String what) {
        if (v == null || v.signum() <= 0 || v.compareTo(MAX_PRICE) > 0) throw BusinessException.invalid(what + " must be more than 0");
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v, String what) {
        if (v == null) return BigDecimal.ZERO.setScale(2);
        if (v.signum() < 0 || v.compareTo(MAX_PRICE) > 0) throw BusinessException.invalid(what + " is invalid");
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
