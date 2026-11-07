package com.buzzer.location;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Multi-city support: launching a new city or locality is configuration, not code. */
@RestController
public class LocationController {
    public record CityInput(@NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Z0-9_]+$") String code,
                            @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 120) String state) {}

    public record LocalityInput(@NotBlank @Size(max = 160) String name, @Pattern(regexp = "^\\d{6}$") String pincode) {}

    public record ActiveInput(boolean active) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public LocationController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/api/v1/locations/cities")
    public ApiResponse<List<Map<String, Object>>> cities() {
        return ApiResponse.of(jdbc.sql("SELECT id, code, name, state FROM cities WHERE active ORDER BY name").query().listOfRows());
    }

    @GetMapping("/api/v1/locations/cities/{cityId}/localities")
    public ApiResponse<List<Map<String, Object>>> localities(@PathVariable UUID cityId, @RequestParam(required = false) String q) {
        return ApiResponse.of(jdbc.sql("""
                SELECT id, name, pincode FROM localities WHERE city_id = :c AND active
                  AND (CAST(:q AS text) IS NULL OR name ILIKE '%' || CAST(:q AS text) || '%' OR pincode LIKE CAST(:q AS text) || '%')
                ORDER BY name""").param("c", cityId).param("q", q).query().listOfRows());
    }

    /**
     * Resolves a GPS position (from the device's "use current location") to the nearest serviceable city and
     * locality, using stored locality centre points - no external geocoding provider required.
     */
    @GetMapping("/api/v1/locations/nearest")
    public ApiResponse<Map<String, Object>> nearest(@RequestParam double lat, @RequestParam double lng) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) throw BusinessException.invalid("Invalid coordinates");
        String haversine = "6371 * 2 * asin(sqrt(power(sin(radians((%s - :lat) / 2)), 2) + cos(radians(:lat)) * cos(radians(%s)) * power(sin(radians((%s - :lng) / 2)), 2)))";
        Map<String, Object> city = jdbc.sql("SELECT id, name, service_radius_km, " + haversine.formatted("latitude", "latitude", "longitude")
                        + " AS distance_km FROM cities WHERE active AND latitude IS NOT NULL ORDER BY distance_km LIMIT 1")
                .param("lat", lat).param("lng", lng).query().listOfRows().stream().findFirst().orElse(null);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("latitude", lat);
        out.put("longitude", lng);
        if (city == null) {
            out.put("in_service_area", false);
            return ApiResponse.of(out);
        }
        double cityDistance = ((Number) city.get("distance_km")).doubleValue();
        boolean inArea = cityDistance <= ((Number) city.get("service_radius_km")).doubleValue();
        out.put("in_service_area", inArea);
        out.put("city_id", city.get("id"));
        out.put("city_name", city.get("name"));
        out.put("city_distance_km", Math.round(cityDistance * 10) / 10.0);
        if (inArea) {
            jdbc.sql("SELECT id, name, pincode, " + haversine.formatted("latitude", "latitude", "longitude")
                            + " AS distance_km FROM localities WHERE city_id = :c AND active AND latitude IS NOT NULL ORDER BY distance_km LIMIT 1")
                    .param("lat", lat).param("lng", lng).param("c", city.get("id")).query().listOfRows().stream().findFirst()
                    .ifPresent(l -> {
                        out.put("locality_id", l.get("id"));
                        out.put("locality_name", l.get("name"));
                        out.put("pincode", l.get("pincode"));
                        out.put("locality_distance_km", Math.round(((Number) l.get("distance_km")).doubleValue() * 10) / 10.0);
                    });
        }
        return ApiResponse.of(out);
    }

    @GetMapping("/api/v1/admin/locations/cities")
    public ApiResponse<List<Map<String, Object>>> adminCities() {
        return ApiResponse.of(jdbc.sql("""
                SELECT c.id, c.code, c.name, c.state, c.active,
                  (SELECT count(*) FROM localities l WHERE l.city_id = c.id) AS locality_count
                FROM cities c ORDER BY c.name""").query().listOfRows());
    }

    @PostMapping("/api/v1/admin/locations/cities")
    public ApiResponse<Map<String, Object>> createCity(@Valid @RequestBody CityInput in) {
        UUID id = jdbc.sql("INSERT INTO cities(code, name, state, active) VALUES (:c, :n, :s, FALSE) RETURNING id")
                .param("c", in.code()).param("n", in.name()).param("s", in.state()).query(UUID.class).single();
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), "CITY_CREATED", "CITY", id, Map.of("code", in.code()));
        return ApiResponse.of(Map.of("id", id, "active", false));
    }

    @PatchMapping("/api/v1/admin/locations/cities/{id}")
    public ApiResponse<Map<String, Object>> setCityActive(@PathVariable UUID id, @RequestBody ActiveInput in) {
        if (jdbc.sql("UPDATE cities SET active = :a WHERE id = :id").param("a", in.active()).param("id", id).update() == 0) {
            throw BusinessException.notFound("City");
        }
        audit.record(CurrentUser.id(), CurrentUser.primaryRole(), in.active() ? "CITY_LAUNCHED" : "CITY_DEACTIVATED", "CITY", id, Map.of());
        return ApiResponse.of(Map.of("id", id, "active", in.active()));
    }

    @PostMapping("/api/v1/admin/locations/cities/{cityId}/localities")
    public ApiResponse<Map<String, Object>> createLocality(@PathVariable UUID cityId, @Valid @RequestBody LocalityInput in) {
        UUID id = jdbc.sql("INSERT INTO localities(city_id, name, pincode) VALUES (:c, :n, :p) RETURNING id")
                .param("c", cityId).param("n", in.name()).param("p", in.pincode()).query(UUID.class).single();
        return ApiResponse.of(Map.of("id", id));
    }
}
