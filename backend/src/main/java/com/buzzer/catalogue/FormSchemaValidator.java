package com.buzzer.catalogue;

import com.buzzer.common.BusinessException;
import com.buzzer.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side validation of dynamic request forms. The frontend renders the same schema, but the
 * backend never trusts it: every submission is re-validated against the stored schema.
 *
 * Supported field types: text, textarea, number, select, multiselect, date, datetime, boolean, photo.
 */
@Component
public class FormSchemaValidator {
    public static final Set<String> TYPES = Set.of("text", "textarea", "number", "select", "multiselect", "date", "datetime", "boolean", "photo");

    /** Validates the schema definition itself (used when admins save a form). */
    public void validateSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) return;
        JsonNode fields = schema.get("fields");
        if (fields == null || !fields.isArray()) throw BusinessException.invalid("Form schema must contain a 'fields' array");
        Set<String> keys = new java.util.HashSet<>();
        for (JsonNode f : fields) {
            String key = text(f, "key");
            String type = text(f, "type");
            if (key == null || !key.matches("^[a-z][a-z0-9_]{0,49}$")) throw BusinessException.invalid("Invalid field key: " + key);
            if (!keys.add(key)) throw BusinessException.invalid("Duplicate field key: " + key);
            if (type == null || !TYPES.contains(type)) throw BusinessException.invalid("Unsupported field type for " + key + ": " + type);
            if (text(f, "label") == null) throw BusinessException.invalid("Field " + key + " needs a label");
            if (("select".equals(type) || "multiselect".equals(type)) && (f.get("options") == null || !f.get("options").isArray() || f.get("options").isEmpty())) {
                throw BusinessException.invalid("Field " + key + " needs options");
            }
        }
    }

    /** Validates answers against the schema; returns only the known keys (unknown input is dropped). */
    public Map<String, Object> validateAnswers(JsonNode schema, Map<String, Object> answers) {
        Map<String, Object> input = answers == null ? Map.of() : answers;
        Map<String, Object> clean = new LinkedHashMap<>();
        Map<String, Object> errors = new LinkedHashMap<>();
        if (schema == null || schema.get("fields") == null) return clean;
        for (JsonNode f : schema.get("fields")) {
            String key = text(f, "key");
            String type = text(f, "type");
            boolean required = f.path("required").asBoolean(false);
            Object value = input.get(key);
            if (isEmpty(value)) {
                if (required) errors.put(key, f.path("label").asText(key) + " is required");
                continue;
            }
            String err = check(f, type, value);
            if (err != null) errors.put(key, err);
            else clean.put(key, value);
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Please correct the highlighted fields", Map.of("fields", errors));
        }
        return clean;
    }

    private String check(JsonNode f, String type, Object value) {
        switch (type) {
            case "text", "textarea" -> {
                if (!(value instanceof String s)) return "Must be text";
                int min = f.path("minLength").asInt(0);
                int max = f.path("maxLength").asInt("text".equals(type) ? 300 : 4000);
                if (s.trim().length() < min) return "Must be at least " + min + " characters";
                if (s.length() > max) return "Must be at most " + max + " characters";
            }
            case "number" -> {
                double d;
                if (value instanceof Number n) d = n.doubleValue();
                else {
                    try {
                        d = Double.parseDouble(String.valueOf(value));
                    } catch (NumberFormatException e) {
                        return "Must be a number";
                    }
                }
                if (f.has("min") && d < f.get("min").asDouble()) return "Must be at least " + f.get("min").asText();
                if (f.has("max") && d > f.get("max").asDouble()) return "Must be at most " + f.get("max").asText();
            }
            case "select" -> {
                if (!options(f).contains(String.valueOf(value))) return "Choose one of the listed options";
            }
            case "multiselect" -> {
                if (!(value instanceof Collection<?> c) || c.isEmpty()) return "Choose at least one option";
                List<String> opts = options(f);
                for (Object o : c) if (!opts.contains(String.valueOf(o))) return "Invalid option: " + o;
            }
            case "date" -> {
                try {
                    LocalDate.parse(String.valueOf(value));
                } catch (DateTimeParseException e) {
                    return "Must be a date (YYYY-MM-DD)";
                }
            }
            case "datetime" -> {
                try {
                    OffsetDateTime.parse(String.valueOf(value));
                } catch (DateTimeParseException e) {
                    return "Must be an ISO date-time";
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) return "Must be yes or no";
            }
            case "photo" -> {
                if (!(value instanceof Collection<?> c)) return "Must be a list of uploaded file references";
                int maxItems = f.path("maxItems").asInt(5);
                if (c.size() > maxItems) return "At most " + maxItems + " files";
            }
            default -> {
                return "Unsupported field";
            }
        }
        return null;
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isBlank()) || (v instanceof Collection<?> c && c.isEmpty());
    }

    private static List<String> options(JsonNode f) {
        List<String> out = new ArrayList<>();
        f.path("options").forEach(o -> out.add(o.asText()));
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
