package com.buzzer.customer;

import com.buzzer.agent.AgentAllocationService;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.Json;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Customer onboarding choice: subscribe for a dedicated agent (matched on languages, area and availability)
 * or explore the app first. Explore customers can subscribe any time from Home or Profile.
 */
@Service
public class AgentSubscriptionService {
    /** Languages a customer can choose; codes match {@code agents.languages}. */
    public static final Map<String, String> LANGUAGES = languages();
    public static final List<String> CONTACT_METHODS = List.of("CALL", "WHATSAPP", "IN_APP_CHAT");
    public static final List<String> CONTACT_TIMES = List.of("ANYTIME", "MORNING", "AFTERNOON", "EVENING");

    public record Preferences(String fullName, List<String> languages, String contactMethod, String contactTime,
                              List<UUID> interestCategoryIds, UUID localityId, String notes) {}

    private final JdbcClient jdbc;
    private final AgentAllocationService allocation;
    private final AuditService audit;
    private final Json json;

    public AgentSubscriptionService(JdbcClient jdbc, AgentAllocationService allocation, AuditService audit, Json json) {
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.audit = audit;
        this.json = json;
    }

    private static Map<String, String> languages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("te", "Telugu");
        m.put("en", "English");
        m.put("hi", "Hindi");
        m.put("ur", "Urdu");
        m.put("ta", "Tamil");
        m.put("kn", "Kannada");
        m.put("ml", "Malayalam");
        m.put("mr", "Marathi");
        m.put("bn", "Bengali");
        return m;
    }

    /** What the app needs to decide between the welcome screen and Home, plus the choices for the subscribe form. */
    public Map<String, Object> status(UUID userId) {
        Map<String, Object> row = jdbc.sql("""
                SELECT u.full_name, cp.agent_plan, cp.onboarded_at, cp.subscribed_at, cp.agent_preferences::text AS prefs,
                       COALESCE(cp.preferred_language, u.preferred_language) AS preferred_language
                FROM users u JOIN customer_profiles cp ON cp.user_id = u.id WHERE u.id = :u""")
                .param("u", userId).query().listOfRows().stream().findFirst().orElseThrow(() -> BusinessException.notFound("Customer profile"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("full_name", row.get("full_name"));
        out.put("plan", row.get("agent_plan"));
        out.put("onboarded", row.get("onboarded_at") != null);
        out.put("subscribed_at", row.get("subscribed_at"));
        out.put("preferences", row.get("prefs") == null ? null : json.map((String) row.get("prefs")));
        out.put("preferred_language", row.get("preferred_language"));
        out.put("agent", agentCard(userId).orElse(null));
        List<Map<String, String>> langs = new ArrayList<>();
        LANGUAGES.forEach((code, label) -> langs.add(Map.of("code", code, "label", label)));
        out.put("language_options", langs);
        out.put("contact_methods", CONTACT_METHODS);
        out.put("contact_times", CONTACT_TIMES);
        return out;
    }

    @Transactional
    public Map<String, Object> subscribe(UUID userId, Preferences in) {
        List<String> langs = new ArrayList<>(new LinkedHashSet<>(in.languages() == null ? List.of() : in.languages().stream()
                .filter(l -> l != null && !l.isBlank()).map(l -> l.trim().toLowerCase()).toList()));
        if (langs.isEmpty()) throw BusinessException.invalid("Choose at least one language you are comfortable with");
        if (langs.size() > 5) throw BusinessException.invalid("Choose up to 5 languages");
        for (String l : langs) if (!LANGUAGES.containsKey(l)) throw BusinessException.invalid("Unsupported language: " + l);
        String method = in.contactMethod() == null ? "CALL" : in.contactMethod();
        String time = in.contactTime() == null ? "ANYTIME" : in.contactTime();
        if (!CONTACT_METHODS.contains(method)) throw BusinessException.invalid("Unsupported contact method");
        if (!CONTACT_TIMES.contains(time)) throw BusinessException.invalid("Unsupported contact time");
        List<UUID> interests = in.interestCategoryIds() == null ? List.of() : in.interestCategoryIds().stream().distinct().limit(25).toList();
        if (in.notes() != null && in.notes().length() > 500) throw BusinessException.invalid("Notes must be 500 characters or less");
        if (in.fullName() != null && !in.fullName().isBlank()) {
            if (in.fullName().trim().length() > 160) throw BusinessException.invalid("Name is too long");
            jdbc.sql("UPDATE users SET full_name = :n, updated_at = now() WHERE id = :u").param("n", in.fullName().trim()).param("u", userId).update();
        }
        if (in.localityId() != null) {
            Integer ok = jdbc.sql("""
                    SELECT count(*) FROM localities l JOIN customer_profiles cp ON cp.city_id = l.city_id WHERE l.id = :l AND cp.user_id = :u""")
                    .param("l", in.localityId()).param("u", userId).query(Integer.class).single();
            if (ok == 0) throw BusinessException.invalid("Choose an area in your city");
        }

        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("languages", langs);
        prefs.put("contact_method", method);
        prefs.put("contact_time", time);
        prefs.put("interest_category_ids", interests.stream().map(UUID::toString).toList());
        prefs.put("notes", in.notes() == null || in.notes().isBlank() ? null : in.notes().trim());
        jdbc.sql("""
                UPDATE customer_profiles SET agent_plan = 'DEDICATED', agent_preferences = CAST(:p AS jsonb), preferred_language = :lang,
                  locality_id = COALESCE(:loc, locality_id), onboarded_at = COALESCE(onboarded_at, now()), subscribed_at = COALESCE(subscribed_at, now())
                WHERE user_id = :u""")
                .param("p", json.write(prefs)).param("lang", langs.getFirst()).param("loc", in.localityId()).param("u", userId).update();
        jdbc.sql("UPDATE users SET preferred_language = :lang, updated_at = now() WHERE id = :u").param("lang", langs.getFirst()).param("u", userId).update();

        boolean existing = allocation.activeLink(userId, "PRIMARY").isPresent();
        Optional<UUID> agent = allocation.allocatePrimary(userId, null, "SUBSCRIBED_DEDICATED_AGENT");
        audit.record(userId, "CUSTOMER", existing ? "AGENT_PREFERENCES_UPDATED" : "SUBSCRIBED_DEDICATED_AGENT", "USER", userId,
                Map.of("languages", String.join(",", langs), "agentAssigned", agent.isPresent()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", "DEDICATED");
        out.put("already_had_agent", existing);
        out.put("agent", agentCard(userId).orElse(null));
        out.put("pending", agent.isEmpty());
        return out;
    }

    /** "Explore the app first": no dedicated agent; never downgrades an existing subscription. */
    @Transactional
    public Map<String, Object> explore(UUID userId) {
        jdbc.sql("""
                UPDATE customer_profiles SET agent_plan = COALESCE(agent_plan, 'EXPLORE'), onboarded_at = COALESCE(onboarded_at, now())
                WHERE user_id = :u""").param("u", userId).update();
        audit.record(userId, "CUSTOMER", "ONBOARDING_EXPLORE", "USER", userId, Map.of());
        String plan = jdbc.sql("SELECT agent_plan FROM customer_profiles WHERE user_id = :u").param("u", userId).query(String.class).single();
        return Map.of("plan", plan, "onboarded", true);
    }

    /** Customers registered by an agent are dedicated from the start. */
    public void markDedicatedByAgent(UUID userId) {
        jdbc.sql("""
                UPDATE customer_profiles SET agent_plan = 'DEDICATED', onboarded_at = COALESCE(onboarded_at, now()),
                  subscribed_at = COALESCE(subscribed_at, now()) WHERE user_id = :u""").param("u", userId).update();
    }

    /** Primary agent with the languages they share with the customer (for "Speaks Telugu & English"). */
    public Optional<Map<String, Object>> agentCard(UUID userId) {
        return jdbc.sql("""
                SELECT a.id AS agent_id, a.display_name, a.photo_url, a.languages, a.availability, a.wing, u.phone,
                       (a.availability = 'AVAILABLE' AND (now() AT TIME ZONE 'Asia/Kolkata')::time BETWEEN a.shift_start AND a.shift_end) AS online,
                       to_char(a.shift_start, 'HH24:MI') AS shift_start, to_char(a.shift_end, 'HH24:MI') AS shift_end,
                       array_to_string(ARRAY(SELECT lang FROM jsonb_array_elements_text(COALESCE(cp.agent_preferences->'languages', '[]'::jsonb)) lang
                             WHERE lang = ANY(string_to_array(lower(replace(a.languages, ' ', '')), ','))), ',') AS shared_languages
                FROM customer_agent_links l JOIN agents a ON a.id = l.agent_id JOIN users u ON u.id = a.user_id
                JOIN customer_profiles cp ON cp.user_id = l.customer_user_id
                WHERE l.customer_user_id = :c AND l.link_role = 'PRIMARY' AND l.active""")
                .param("c", userId).query().listOfRows().stream().findFirst()
                .map(m -> {
                    Map<String, Object> card = new LinkedHashMap<>(m);
                    String shared = (String) m.get("shared_languages");
                    List<String> codes = shared == null || shared.isBlank() ? List.of() : List.of(shared.split(","));
                    card.put("shared_languages", codes.stream().map(c -> LANGUAGES.getOrDefault(c, c)).toList());
                    return card;
                });
    }
}
