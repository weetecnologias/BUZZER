package com.buzzer.order;

import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.buzzer.marketplace.MarketplaceService;
import com.buzzer.marketplace.MarketplaceSql;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Product cart: products from ONE shop at a time (MVP rule). Every view re-checks price, availability and minimum order
 * against the shop's current listing; checkout refuses a cart whose prices changed until the customer accepts them.
 */
@Service
public class ProductCartService {
    /** One cart line, re-priced against the live listing. */
    public record Line(UUID listingId, UUID variantId, UUID productId, UUID subcategoryId, UUID categoryId, String productName, String variantName,
                       String brand, String unit, String specs, int quantity, BigDecimal unitPrice, BigDecimal seenPrice, BigDecimal mrp,
                       BigDecimal taxRate, BigDecimal lineTotal, BigDecimal taxIncluded, String stockStatus, OffsetDateTime stockUpdatedAt,
                       int moq, Integer maxOrderQty, int leadTimeDays, boolean priceChanged, List<String> issues) {}

    /** The shop's branch serving this cart. */
    public record Location(UUID locationId, String name, String address, String locality, String openingHours, String phone, boolean pickupEnabled,
                           boolean deliveryEnabled, BigDecimal deliveryFee, BigDecimal freeDeliveryAbove, BigDecimal minOrderValue,
                           BigDecimal serviceRadiusKm, BigDecimal distanceKm, UUID cityId) {}

    public record Quote(UUID cartId, UUID partnerId, String shopName, String shopPhone, Location location, List<Line> lines, BigDecimal itemsTotal,
                        BigDecimal taxIncluded, boolean deliveryAvailable, String deliveryUnavailableReason, BigDecimal deliveryFee) {
        public boolean hasIssues() {
            return lines.stream().anyMatch(l -> !l.issues().isEmpty());
        }

        public boolean hasPriceChanges() {
            return lines.stream().anyMatch(Line::priceChanged);
        }
    }

    private final JdbcClient jdbc;
    private final MarketplaceService marketplace;

    public ProductCartService(JdbcClient jdbc, MarketplaceService marketplace) {
        this.jdbc = jdbc;
        this.marketplace = marketplace;
    }

    /** Price for a quantity: the bulk tier price once the quantity reaches it. */
    static BigDecimal unitPrice(BigDecimal price, BigDecimal bulkPrice, Integer bulkMinQty, int qty) {
        return bulkPrice != null && bulkMinQty != null && qty >= bulkMinQty ? bulkPrice : price;
    }

    /** Tax contained in a tax-inclusive amount. */
    static BigDecimal taxIncluded(BigDecimal amount, BigDecimal rate) {
        if (rate == null || rate.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return amount.multiply(rate).divide(rate.add(BigDecimal.valueOf(100)), 2, RoundingMode.HALF_UP);
    }

    @Transactional
    public Map<String, Object> add(UUID customer, UUID listingId, int quantity, boolean replaceCart) {
        if (listingId == null) throw BusinessException.invalid("Choose a product offer");
        record L(UUID partnerId, String shopName, BigDecimal price, BigDecimal bulkPrice, Integer bulkMinQty, int moq, Integer maxOrderQty,
                 String stockStatus, boolean visible) {}
        L l = jdbc.sql("SELECT p.id AS partner_id, p.display_name AS shop_name, l.price, l.bulk_price, l.bulk_min_qty, l.moq, l.max_order_qty, l.stock_status, ("
                        + MarketplaceSql.VISIBLE_LISTING + ") AS visible " + MarketplaceSql.LISTING_JOINS + " WHERE l.id = :id")
                .param("id", listingId).query(L.class).optional().orElseThrow(() -> BusinessException.notFound("Product offer"));
        if (!l.visible()) throw new BusinessException(ErrorCode.ITEM_UNAVAILABLE, "This shop no longer sells this product");
        if ("OUT_OF_STOCK".equals(l.stockStatus())) throw new BusinessException(ErrorCode.ITEM_UNAVAILABLE, "This product is out of stock at " + l.shopName());
        record Cart(UUID id, UUID partnerId, String shopName) {}
        Cart cart = jdbc.sql("""
                SELECT pc.id, pc.partner_id, p.display_name AS shop_name FROM product_carts pc JOIN partners p ON p.id = pc.partner_id
                WHERE pc.customer_user_id = :c FOR UPDATE""").param("c", customer).query(Cart.class).optional().orElse(null);
        int existing = 0;
        if (cart != null && !cart.partnerId().equals(l.partnerId())) {
            int items = jdbc.sql("SELECT count(*) FROM product_cart_items WHERE cart_id = :id").param("id", cart.id()).query(Integer.class).single();
            if (items > 0 && !replaceCart) {
                throw new BusinessException(ErrorCode.CART_VENDOR_MISMATCH, "Your cart has items from " + cart.shopName()
                        + ". One order is from one shop - empty the cart to buy from " + l.shopName() + ".",
                        Map.of("cart_shop", cart.shopName(), "new_shop", l.shopName()));
            }
            jdbc.sql("DELETE FROM product_cart_items WHERE cart_id = :id").param("id", cart.id()).update();
            jdbc.sql("UPDATE product_carts SET partner_id = :p, updated_at = now() WHERE id = :id").param("p", l.partnerId()).param("id", cart.id()).update();
        } else if (cart != null) {
            existing = jdbc.sql("SELECT COALESCE(max(quantity), 0) FROM product_cart_items WHERE cart_id = :c AND listing_id = :l")
                    .param("c", cart.id()).param("l", listingId).query(Integer.class).single();
        }
        UUID cartId = cart != null ? cart.id() : jdbc.sql("INSERT INTO product_carts(customer_user_id, partner_id) VALUES (:c, :p) RETURNING id")
                .param("c", customer).param("p", l.partnerId()).query(UUID.class).single();
        int qty = existing + Math.max(quantity, 1);
        if (qty < l.moq()) qty = l.moq(); // first add starts at the shop's minimum order quantity
        if (l.maxOrderQty() != null && qty > l.maxOrderQty()) throw BusinessException.invalid("At most " + l.maxOrderQty() + " per order from this shop");
        if (qty > 10000) throw BusinessException.invalid("Quantity is too large");
        BigDecimal unit = unitPrice(l.price(), l.bulkPrice(), l.bulkMinQty(), qty);
        jdbc.sql("""
                INSERT INTO product_cart_items(cart_id, listing_id, quantity, unit_price_seen) VALUES (:c, :l, :q, :u)
                ON CONFLICT (cart_id, listing_id) DO UPDATE SET quantity = :q, unit_price_seen = :u""")
                .param("c", cartId).param("l", listingId).param("q", qty).param("u", unit).update();
        jdbc.sql("UPDATE product_carts SET updated_at = now() WHERE id = :id").param("id", cartId).update();
        return view(customer, null);
    }

    @Transactional
    public Map<String, Object> setQuantity(UUID customer, UUID listingId, int quantity) {
        UUID cartId = jdbc.sql("SELECT id FROM product_carts WHERE customer_user_id = :c FOR UPDATE").param("c", customer).query(UUID.class)
                .optional().orElseThrow(() -> BusinessException.notFound("Cart"));
        if (quantity <= 0) {
            jdbc.sql("DELETE FROM product_cart_items WHERE cart_id = :c AND listing_id = :l").param("c", cartId).param("l", listingId).update();
            return view(customer, null);
        }
        if (quantity > 10000) throw BusinessException.invalid("Quantity is too large");
        record L(BigDecimal price, BigDecimal bulkPrice, Integer bulkMinQty, int moq, Integer maxOrderQty) {}
        L l = jdbc.sql("""
                SELECT l.price, l.bulk_price, l.bulk_min_qty, l.moq, l.max_order_qty FROM product_cart_items ci JOIN vendor_listings l ON l.id = ci.listing_id
                WHERE ci.cart_id = :c AND ci.listing_id = :l""").param("c", cartId).param("l", listingId).query(L.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Cart item"));
        if (quantity < l.moq()) throw BusinessException.invalid("The shop's minimum order for this product is " + l.moq());
        if (l.maxOrderQty() != null && quantity > l.maxOrderQty()) throw BusinessException.invalid("At most " + l.maxOrderQty() + " per order from this shop");
        jdbc.sql("UPDATE product_cart_items SET quantity = :q, unit_price_seen = :u WHERE cart_id = :c AND listing_id = :l")
                .param("q", quantity).param("u", unitPrice(l.price(), l.bulkPrice(), l.bulkMinQty(), quantity)).param("c", cartId)
                .param("l", listingId).update();
        return view(customer, null);
    }

    /** The customer saw the new prices and accepts them (needed before checkout after a price change). */
    @Transactional
    public Map<String, Object> acceptPrices(UUID customer) {
        jdbc.sql("""
                UPDATE product_cart_items ci SET unit_price_seen = CASE WHEN l.bulk_min_qty IS NOT NULL AND ci.quantity >= l.bulk_min_qty THEN l.bulk_price ELSE l.price END
                FROM product_carts pc, vendor_listings l WHERE pc.id = ci.cart_id AND pc.customer_user_id = :c AND l.id = ci.listing_id""")
                .param("c", customer).update();
        return view(customer, null);
    }

    @Transactional
    public void clear(UUID customer) {
        jdbc.sql("DELETE FROM product_cart_items WHERE cart_id IN (SELECT id FROM product_carts WHERE customer_user_id = :c)").param("c", customer).update();
    }

    public Map<String, Object> view(UUID customer, UUID addressId) {
        Quote q = quote(customer, addressId);
        Map<String, Object> out = new LinkedHashMap<>();
        if (q == null) {
            out.put("items", List.of());
            out.put("item_count", 0);
            return out;
        }
        out.put("shop", Map.of("partner_id", q.partnerId(), "name", q.shopName()));
        out.put("location", q.location() == null ? null : locationMap(q.location()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Line l : q.lines()) items.add(lineMap(l));
        out.put("items", items);
        out.put("item_count", q.lines().stream().mapToInt(Line::quantity).sum());
        out.put("items_total", q.itemsTotal());
        out.put("tax_included", q.taxIncluded());
        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("available", q.location() != null && q.location().pickupEnabled());
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("available", q.deliveryAvailable());
        delivery.put("fee", q.deliveryFee());
        delivery.put("reason", q.deliveryUnavailableReason());
        delivery.put("free_above", q.location() == null ? null : q.location().freeDeliveryAbove());
        delivery.put("min_order_value", q.location() == null ? null : q.location().minOrderValue());
        out.put("pickup", pickup);
        out.put("delivery", delivery);
        out.put("has_issues", q.hasIssues());
        out.put("price_changed", q.hasPriceChanges());
        out.put("stock_note", "Stock is as declared by the shop. The shop confirms availability when it accepts your order.");
        return out;
    }

    /** Re-prices the cart against live listings; for delivery to an address, also checks the shop's delivery area. */
    public Quote quote(UUID customer, UUID addressId) {
        record Cart(UUID id, UUID partnerId, String shopName, String shopPhone) {}
        Cart cart = jdbc.sql("""
                SELECT pc.id, pc.partner_id, p.display_name AS shop_name, p.contact_phone AS shop_phone FROM product_carts pc
                JOIN partners p ON p.id = pc.partner_id WHERE pc.customer_user_id = :c""").param("c", customer).query(Cart.class).optional().orElse(null);
        if (cart == null) return null;
        record Row(UUID listingId, UUID variantId, UUID productId, UUID subcategoryId, UUID categoryId, String productName, String variantName,
                   String brand, String unit, String specs, int quantity, BigDecimal unitPriceSeen, BigDecimal price, BigDecimal mrp, BigDecimal bulkPrice,
                   Integer bulkMinQty, BigDecimal taxRate, String stockStatus, OffsetDateTime stockUpdatedAt, int moq, Integer maxOrderQty,
                   int leadTimeDays, boolean visible) {}
        List<Row> rows = jdbc.sql("""
                SELECT l.id AS listing_id, v.id AS variant_id, pr.id AS product_id, s.id AS subcategory_id, c.id AS category_id,
                       pr.name AS product_name, v.name AS variant_name, b.name AS brand, pr.unit, pr.specs::text AS specs, ci.quantity,
                       ci.unit_price_seen, l.price, l.mrp, l.bulk_price, l.bulk_min_qty, pr.tax_rate_percent AS tax_rate, l.stock_status,
                       l.stock_updated_at, l.moq, l.max_order_qty, l.lead_time_days, (""" + MarketplaceSql.VISIBLE_LISTING + """
                ) AS visible
                """ + MarketplaceSql.LISTING_JOINS + """

                JOIN product_cart_items ci ON ci.listing_id = l.id
                WHERE ci.cart_id = :cart ORDER BY ci.added_at, pr.name""").param("cart", cart.id()).query(Row.class).list();
        if (rows.isEmpty()) return null;
        List<Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (Row r : rows) {
            BigDecimal unit = unitPrice(r.price(), r.bulkPrice(), r.bulkMinQty(), r.quantity());
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(r.quantity())).setScale(2, RoundingMode.HALF_UP);
            BigDecimal lineTax = taxIncluded(lineTotal, r.taxRate());
            List<String> issues = new ArrayList<>();
            if (!r.visible()) issues.add("No longer sold by this shop");
            else if ("OUT_OF_STOCK".equals(r.stockStatus())) issues.add("Out of stock");
            if (r.quantity() < r.moq()) issues.add("Minimum order is " + r.moq());
            if (r.maxOrderQty() != null && r.quantity() > r.maxOrderQty()) issues.add("At most " + r.maxOrderQty() + " per order");
            lines.add(new Line(r.listingId(), r.variantId(), r.productId(), r.subcategoryId(), r.categoryId(), r.productName(), r.variantName(),
                    r.brand(), r.unit(), r.specs(), r.quantity(), unit, r.unitPriceSeen(), r.mrp(), r.taxRate(), lineTotal, lineTax, r.stockStatus(),
                    r.stockUpdatedAt(), r.moq(), r.maxOrderQty(), r.leadTimeDays(), unit.compareTo(r.unitPriceSeen()) != 0, issues));
            total = total.add(lineTotal);
            tax = tax.add(lineTax);
        }
        MarketplaceService.GeoPoint g = marketplace.forAddress(addressId, customer);
        Location loc = jdbc.sql("""
                SELECT loc.location_id, loc.location_name AS name, loc.location_address AS address, loc.location_locality AS locality, loc.opening_hours,
                       loc.location_phone AS phone, loc.pickup_enabled, loc.delivery_enabled, loc.delivery_fee, loc.free_delivery_above,
                       loc.min_order_value, loc.service_radius_km, loc.distance_km, loc.location_city_id AS city_id
                FROM partners p""" + "\n" + MarketplaceSql.NEAREST_LOCATION + " WHERE p.id = :p")
                .param("lat", g.lat()).param("lng", g.lng()).param("p", cart.partnerId()).query(Location.class).optional().orElse(null);
        if (loc != null && loc.locationId() == null) loc = null;
        boolean deliveryOk = false;
        String reason = null;
        BigDecimal fee = BigDecimal.ZERO.setScale(2);
        if (loc == null || !loc.deliveryEnabled()) {
            reason = "This shop offers pickup only";
        } else if (loc.minOrderValue() != null && total.compareTo(loc.minOrderValue()) < 0) {
            reason = "Delivery needs a minimum order of Rs " + loc.minOrderValue().toPlainString();
        } else if (addressId != null && loc.distanceKm() != null && loc.distanceKm().compareTo(loc.serviceRadiusKm()) > 0) {
            reason = "Your address is outside this shop's delivery area (" + loc.serviceRadiusKm().stripTrailingZeros().toPlainString() + " km)";
        } else {
            deliveryOk = true;
            fee = loc.freeDeliveryAbove() != null && total.compareTo(loc.freeDeliveryAbove()) >= 0 ? BigDecimal.ZERO.setScale(2) : loc.deliveryFee();
        }
        return new Quote(cart.id(), cart.partnerId(), cart.shopName(), cart.shopPhone(), loc, lines, total.setScale(2, RoundingMode.HALF_UP),
                tax.setScale(2, RoundingMode.HALF_UP), deliveryOk, reason, fee);
    }

    static Map<String, Object> lineMap(Line l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("listing_id", l.listingId());
        m.put("product_id", l.productId());
        m.put("variant_id", l.variantId());
        m.put("product_name", l.productName());
        m.put("variant_name", l.variantName());
        m.put("brand", l.brand());
        m.put("unit", l.unit());
        m.put("quantity", l.quantity());
        m.put("unit_price", l.unitPrice());
        m.put("previous_price", l.priceChanged() ? l.seenPrice() : null);
        m.put("mrp", l.mrp());
        m.put("line_total", l.lineTotal());
        m.put("tax_rate", l.taxRate());
        m.put("tax_included", l.taxIncluded());
        m.put("stock_status", l.stockStatus());
        m.put("stock_updated_at", l.stockUpdatedAt());
        m.put("moq", l.moq());
        m.put("max_order_qty", l.maxOrderQty());
        m.put("lead_time_days", l.leadTimeDays());
        m.put("price_changed", l.priceChanged());
        m.put("issues", l.issues());
        return m;
    }

    static Map<String, Object> locationMap(Location l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location_id", l.locationId());
        m.put("name", l.name());
        m.put("address", l.address());
        m.put("locality", l.locality());
        m.put("opening_hours", l.openingHours());
        m.put("phone", l.phone());
        m.put("pickup_enabled", l.pickupEnabled());
        m.put("delivery_enabled", l.deliveryEnabled());
        m.put("distance_km", l.distanceKm());
        return m;
    }
}
