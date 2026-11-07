package com.buzzer.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-configurable platform settings (thresholds, SLAs, switches). */
@Service
public class SettingsService {
    private final JdbcClient jdbc;

    public SettingsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String get(String key, String defaultValue) {
        return jdbc.sql("SELECT value FROM platform_settings WHERE key = :k").param("k", key)
                .query(String.class).optional().orElse(defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)).trim());
    }

    public List<Map<String, Object>> all() {
        return jdbc.sql("SELECT key, value, description, updated_at FROM platform_settings ORDER BY key").query().listOfRows();
    }

    public void put(String key, String value, UUID updatedBy) {
        int updated = jdbc.sql("UPDATE platform_settings SET value = :v, updated_by = :u, updated_at = now() WHERE key = :k")
                .param("v", value).param("u", updatedBy).param("k", key).update();
        if (updated == 0) {
            throw BusinessException.notFound("Setting " + key);
        }
    }
}
