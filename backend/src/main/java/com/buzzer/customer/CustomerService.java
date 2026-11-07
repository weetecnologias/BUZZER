package com.buzzer.customer;

import com.buzzer.common.BusinessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {
    public record AddressInput(String label, String line1, String line2, String landmark, UUID cityId, UUID localityId,
                               String pincode, BigDecimal latitude, BigDecimal longitude, Boolean isDefault) {}

    private final JdbcClient jdbc;

    public CustomerService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the customer profile if missing; defaults the city to the launch city. */
    public void ensureProfile(UUID userId, UUID registeredByAgentId) {
        jdbc.sql("""
                INSERT INTO customer_profiles(user_id, city_id, registered_by_agent_id)
                VALUES (:u, (SELECT id FROM cities WHERE active ORDER BY created_at LIMIT 1), :a)
                ON CONFLICT (user_id) DO NOTHING""").param("u", userId).param("a", registeredByAgentId).update();
    }

    public Map<String, Object> profile(UUID userId) {
        return jdbc.sql("""
                SELECT u.id, u.phone, u.full_name, u.email, COALESCE(cp.preferred_language, u.preferred_language) AS preferred_language,
                       cp.city_id, ci.name AS city_name, cp.locality_id, cp.notify_push, cp.notify_sms, cp.notify_whatsapp, cp.notify_email,
                       u.created_at
                FROM users u JOIN customer_profiles cp ON cp.user_id = u.id LEFT JOIN cities ci ON ci.id = cp.city_id
                WHERE u.id = :u""").param("u", userId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("Customer profile"));
    }

    @Transactional
    public void updateProfile(UUID userId, String fullName, String email, String language, UUID cityId, UUID localityId,
                              Boolean push, Boolean sms, Boolean whatsapp, Boolean emailNotify) {
        jdbc.sql("""
                UPDATE users SET full_name = COALESCE(:n, full_name), email = COALESCE(:e, email),
                  preferred_language = COALESCE(:l, preferred_language), updated_at = now() WHERE id = :u""")
                .param("n", fullName).param("e", email).param("l", language).param("u", userId).update();
        jdbc.sql("""
                UPDATE customer_profiles SET city_id = COALESCE(:c, city_id), locality_id = COALESCE(:loc, locality_id),
                  preferred_language = COALESCE(:l, preferred_language),
                  notify_push = COALESCE(:p, notify_push), notify_sms = COALESCE(:s, notify_sms),
                  notify_whatsapp = COALESCE(:w, notify_whatsapp), notify_email = COALESCE(:em, notify_email)
                WHERE user_id = :u""")
                .param("c", cityId).param("loc", localityId).param("l", language).param("p", push).param("s", sms)
                .param("w", whatsapp).param("em", emailNotify).param("u", userId).update();
    }

    public List<Map<String, Object>> addresses(UUID userId) {
        return jdbc.sql("""
                SELECT a.id, a.label, a.line1, a.line2, a.landmark, a.city_id, c.name AS city_name, a.locality_id,
                       l.name AS locality_name, a.pincode, a.latitude, a.longitude, a.is_default
                FROM addresses a JOIN cities c ON c.id = a.city_id LEFT JOIN localities l ON l.id = a.locality_id
                WHERE a.user_id = :u AND a.deleted_at IS NULL ORDER BY a.is_default DESC, a.created_at""")
                .param("u", userId).query().listOfRows();
    }

    @Transactional
    public UUID addAddress(UUID userId, AddressInput in) {
        validate(in);
        boolean first = jdbc.sql("SELECT count(*) FROM addresses WHERE user_id = :u AND deleted_at IS NULL")
                .param("u", userId).query(Integer.class).single() == 0;
        boolean makeDefault = first || Boolean.TRUE.equals(in.isDefault());
        if (makeDefault) clearDefault(userId);
        return jdbc.sql("""
                INSERT INTO addresses(user_id, label, line1, line2, landmark, city_id, locality_id, pincode, latitude, longitude, is_default)
                VALUES (:u, :label, :l1, :l2, :lm, :c, :loc, :pin, :lat, :lng, :def) RETURNING id""")
                .param("u", userId).param("label", in.label()).param("l1", in.line1().trim()).param("l2", in.line2())
                .param("lm", in.landmark()).param("c", in.cityId()).param("loc", in.localityId()).param("pin", in.pincode())
                .param("lat", in.latitude()).param("lng", in.longitude()).param("def", makeDefault).query(UUID.class).single();
    }

    @Transactional
    public void updateAddress(UUID userId, UUID addressId, AddressInput in) {
        validate(in);
        if (Boolean.TRUE.equals(in.isDefault())) clearDefault(userId);
        int n = jdbc.sql("""
                UPDATE addresses SET label = :label, line1 = :l1, line2 = :l2, landmark = :lm, city_id = :c, locality_id = :loc,
                  pincode = :pin, latitude = :lat, longitude = :lng, is_default = COALESCE(:def, is_default)
                WHERE id = :id AND user_id = :u AND deleted_at IS NULL""")
                .param("label", in.label()).param("l1", in.line1().trim()).param("l2", in.line2()).param("lm", in.landmark())
                .param("c", in.cityId()).param("loc", in.localityId()).param("pin", in.pincode()).param("lat", in.latitude())
                .param("lng", in.longitude()).param("def", in.isDefault()).param("id", addressId).param("u", userId).update();
        if (n == 0) throw BusinessException.notFound("Address");
    }

    public void deleteAddress(UUID userId, UUID addressId) {
        // Soft delete: historic requests keep their address snapshot.
        int n = jdbc.sql("UPDATE addresses SET deleted_at = now(), is_default = FALSE WHERE id = :id AND user_id = :u AND deleted_at IS NULL")
                .param("id", addressId).param("u", userId).update();
        if (n == 0) throw BusinessException.notFound("Address");
    }

    private void clearDefault(UUID userId) {
        jdbc.sql("UPDATE addresses SET is_default = FALSE WHERE user_id = :u").param("u", userId).update();
    }

    private void validate(AddressInput in) {
        if (in.label() == null || !List.of("HOME", "WORK", "OTHER").contains(in.label())) throw BusinessException.invalid("Label must be HOME, WORK or OTHER");
        if (in.line1() == null || in.line1().trim().length() < 5) throw BusinessException.invalid("Enter the full address (house / street)");
        if (in.pincode() == null || !in.pincode().matches("^[1-9]\\d{5}$")) throw BusinessException.invalid("Enter a valid 6-digit pincode");
        if (in.cityId() == null) throw BusinessException.invalid("City is required");
        if (in.latitude() != null && (in.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0)) throw BusinessException.invalid("Invalid latitude");
        if (in.longitude() != null && (in.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0)) throw BusinessException.invalid("Invalid longitude");
        if (in.localityId() != null) {
            Integer ok = jdbc.sql("SELECT count(*) FROM localities WHERE id = :l AND city_id = :c").param("l", in.localityId())
                    .param("c", in.cityId()).query(Integer.class).single();
            if (ok == 0) throw BusinessException.invalid("Locality does not belong to the selected city");
        }
    }
}
