package com.buzzer.marketplace;

/**
 * SQL fragments shared by every marketplace query, so the same visibility rules apply to shop discovery, product search,
 * product pages, carts and checkout. A listing is visible only when ALL hold:
 * <ul>
 *   <li>the shop is approved, KYC-verified, open (AVAILABLE) and holds an approved shop-type role;</li>
 *   <li>the shop has an approved capability for the product's subcategory;</li>
 *   <li>the listing is ACTIVE (admin-reviewed), the product ACTIVE and its category and business vertical switched on.</li>
 * </ul>
 */
public final class MarketplaceSql {
    private MarketplaceSql() {}

    public static final String SHOP_ROLES = "('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR')";

    /** Alias p = partners. */
    public static final String VISIBLE_SHOP = """
            p.registration_status = 'APPROVED' AND p.kyc_status = 'VERIFIED' AND p.availability = 'AVAILABLE'
              AND EXISTS (SELECT 1 FROM partner_roles prl WHERE prl.partner_id = p.id AND prl.status = 'APPROVED'
                          AND prl.role IN ('RETAIL_SHOP','WHOLESALER','DISTRIBUTOR'))""";

    /** Joins listing l, variant v, product pr, subcategory s, category c, partner p, brand b. */
    public static final String LISTING_JOINS = """
            FROM vendor_listings l
            JOIN product_variants v ON v.id = l.variant_id
            JOIN products pr ON pr.id = v.product_id
            JOIN subcategories s ON s.id = pr.subcategory_id
            JOIN categories c ON c.id = s.category_id
            JOIN partners p ON p.id = l.partner_id
            LEFT JOIN brands b ON b.id = pr.brand_id""";

    /** Uses the aliases of {@link #LISTING_JOINS}. */
    public static final String VISIBLE_LISTING = """
            l.status = 'ACTIVE' AND v.active AND pr.status = 'ACTIVE' AND s.active AND c.active
              AND c.vertical IN (SELECT code FROM platform_verticals WHERE enabled)
              AND EXISTS (SELECT 1 FROM partner_capabilities pc WHERE pc.partner_id = p.id AND pc.status = 'APPROVED'
                          AND pc.subcategory_id = pr.subcategory_id)""" + " AND " + VISIBLE_SHOP;

    /**
     * Nearest active location of partner p to the point (:lat, :lng), as alias loc. distance_km is NULL when the customer
     * or the shop has no coordinates (then city-level matching applies).
     */
    public static final String NEAREST_LOCATION = """
            LEFT JOIN LATERAL (
              SELECT pl.id AS location_id, pl.name AS location_name, pl.address AS location_address, pl.opening_hours,
                     pl.pickup_enabled, pl.delivery_enabled, pl.delivery_fee, pl.free_delivery_above, pl.min_order_value,
                     pl.service_radius_km, pl.phone AS location_phone, pl.city_id AS location_city_id, plo.name AS location_locality,
                     CASE WHEN CAST(:lat AS numeric) IS NULL OR pl.latitude IS NULL THEN NULL
                          ELSE round(CAST(6371 * 2 * asin(sqrt(
                                 power(sin(radians(CAST(:lat AS numeric) - pl.latitude) / 2), 2)
                                 + cos(radians(pl.latitude)) * cos(radians(CAST(:lat AS numeric)))
                                   * power(sin(radians(CAST(:lng AS numeric) - pl.longitude) / 2), 2))) AS numeric), 1) END AS distance_km
              FROM partner_locations pl LEFT JOIN localities plo ON plo.id = pl.locality_id
              WHERE pl.partner_id = p.id AND pl.active
              ORDER BY distance_km NULLS LAST, pl.is_primary DESC, pl.created_at
              LIMIT 1) loc ON TRUE""";

    /** Unit price for a quantity: the bulk tier price once the quantity reaches the tier. Alias l. */
    public static final String UNIT_PRICE_FOR_QTY = "CASE WHEN l.bulk_min_qty IS NOT NULL AND :qty >= l.bulk_min_qty THEN l.bulk_price ELSE l.price END";
}
