package com.buzzer.request;

import com.buzzer.common.AuditService;
import com.buzzer.common.BusinessException;
import com.buzzer.common.BuzzerProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Private request attachments (photos, BOQs, prescriptions, documents).
 * <ul>
 *   <li>Type is detected from the file's bytes (PNG, JPEG, WebP, PDF) - never from the name or the client's header.</li>
 *   <li>Max 5 MB per file and 10 files per request.</li>
 *   <li>Files are never public: viewers who may see a file get a short-lived HMAC-signed link (default 15 minutes).</li>
 *   <li>PARTNERS visibility shares a file with the partners invited to quote; PRIVATE stays with customer, agent and admin.</li>
 * </ul>
 */
@Service
public class AttachmentService {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_PER_REQUEST = 10;
    private static final long LINK_SECONDS = 15 * 60;

    public record FileData(String fileName, String contentType, byte[] data) {}

    private final JdbcClient jdbc;
    private final RequestRepository requests;
    private final AuditService audit;
    private final byte[] key;

    public AttachmentService(JdbcClient jdbc, RequestRepository requests, AuditService audit, BuzzerProperties props) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.audit = audit;
        // Separate key derived from the JWT secret so attachment links can't be used as tokens and vice versa.
        this.key = ("attachments:" + props.security().jwtSecret()).getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public Map<String, Object> upload(UUID requestId, UUID uploaderUserId, String role, MultipartFile file, String visibility, String note) {
        return upload(requestId, uploaderUserId, role, file, visibility, note, "GENERAL");
    }

    /** purpose: GENERAL, or BEFORE / AFTER for a provider's completion evidence. */
    @Transactional
    public Map<String, Object> upload(UUID requestId, UUID uploaderUserId, String role, MultipartFile file, String visibility, String note, String purpose) {
        String why = purpose == null || purpose.isBlank() ? "GENERAL" : purpose.toUpperCase();
        if (!List.of("GENERAL", "BEFORE", "AFTER").contains(why)) throw BusinessException.invalid("Purpose must be BEFORE or AFTER");
        RequestRepository.Row r = requests.lock(requestId);
        if (java.util.Set.of("SETTLED", "CANCELLED", "REJECTED", "EXPIRED", "REFUNDED", "VENDOR_REJECTED").contains(r.status())) throw BusinessException.invalid("Attachments can't be added to a closed request");
        if (file == null || file.isEmpty()) throw BusinessException.invalid("Choose a file to upload");
        if (file.getSize() > MAX_BYTES) throw BusinessException.invalid("Files must be 5 MB or smaller");
        String vis = visibility == null || visibility.isBlank() ? "PRIVATE" : visibility.toUpperCase();
        if (!List.of("PRIVATE", "PARTNERS").contains(vis)) throw BusinessException.invalid("Visibility must be PRIVATE or PARTNERS");
        if (note != null && note.length() > 200) throw BusinessException.invalid("Note must be 200 characters or less");
        Integer count = jdbc.sql("SELECT count(*) FROM request_attachments WHERE request_id = :r AND deleted_at IS NULL").param("r", requestId)
                .query(Integer.class).single();
        if (count >= MAX_PER_REQUEST) throw BusinessException.invalid("A request can have at most " + MAX_PER_REQUEST + " attachments");
        byte[] data;
        try {
            data = file.getBytes();
        } catch (java.io.IOException e) {
            throw BusinessException.invalid("Could not read the file");
        }
        String type = sniff(data);
        if (type == null) throw BusinessException.invalid("Only photos (PNG, JPG, WebP) and PDF documents can be attached");
        String name = safeName(file.getOriginalFilename(), type);
        UUID id = jdbc.sql("""
                INSERT INTO request_attachments(request_id, uploaded_by, uploader_role, file_name, content_type, size_bytes, data, visibility, note, purpose)
                VALUES (:r, :u, :role, :n, :t, :s, :d, :v, :note, :purpose) RETURNING id""")
                .param("r", requestId).param("u", uploaderUserId).param("role", role).param("n", name).param("t", type).param("s", data.length)
                .param("d", data).param("v", vis).param("note", note == null || note.isBlank() ? null : note.trim()).param("purpose", why)
                .query(UUID.class).single();
        requests.note(requestId, r.status(), uploaderUserId, role, switch (why) {
            case "BEFORE" -> "Before photo added by the professional";
            case "AFTER" -> "After photo (finished work) added by the professional";
            default -> "Attachment added: " + name + ("PARTNERS".equals(vis) ? " (shared with quoting partners)" : "");
        });
        audit.record(uploaderUserId, role, "ATTACHMENT_UPLOADED", "REQUEST", requestId, Map.of("attachment", id.toString(), "type", type, "bytes", data.length));
        return metadata(id).orElseThrow();
    }

    /** Customer removes their own attachment (soft delete). */
    @Transactional
    public void delete(UUID requestId, UUID attachmentId, UUID userId) {
        int n = jdbc.sql("""
                UPDATE request_attachments SET deleted_at = now()
                WHERE id = :a AND request_id = :r AND uploaded_by = :u AND deleted_at IS NULL""")
                .param("a", attachmentId).param("r", requestId).param("u", userId).update();
        if (n == 0) throw BusinessException.notFound("Attachment");
        RequestRepository.Row r = requests.get(requestId);
        requests.note(requestId, r.status(), userId, "CUSTOMER", "Attachment removed");
    }

    /** Attachments a viewer may see, each with a fresh signed link. partnersOnly = the viewer is an invited partner. */
    public List<Map<String, Object>> list(UUID requestId, boolean partnersOnly) {
        List<UUID> ids = jdbc.sql("""
                SELECT id FROM request_attachments WHERE request_id = :r AND deleted_at IS NULL AND (NOT :p OR visibility = 'PARTNERS')
                ORDER BY created_at""").param("r", requestId).param("p", partnersOnly).query(UUID.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : ids) metadata(id).ifPresent(out::add);
        return out;
    }

    /** Files one user uploaded to a request (a provider's own evidence). */
    public List<Map<String, Object>> listBy(UUID requestId, UUID userId) {
        List<UUID> ids = jdbc.sql("SELECT id FROM request_attachments WHERE request_id = :r AND uploaded_by = :u AND deleted_at IS NULL ORDER BY created_at")
                .param("r", requestId).param("u", userId).query(UUID.class).list();
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : ids) metadata(id).ifPresent(out::add);
        return out;
    }

    private Optional<Map<String, Object>> metadata(UUID id) {
        return jdbc.sql("""
                SELECT a.id, a.file_name, a.content_type, a.size_bytes, a.visibility, a.note, a.uploader_role, a.purpose, a.created_at, u.full_name AS uploaded_by_name
                FROM request_attachments a JOIN users u ON u.id = a.uploaded_by WHERE a.id = :id AND a.deleted_at IS NULL""")
                .param("id", id).query().listOfRows().stream().findFirst().map(m -> {
                    Map<String, Object> out = new LinkedHashMap<>(m);
                    out.put("url", signedPath(id));
                    return out;
                });
    }

    /** Relative URL valid for {@link #LINK_SECONDS}: /api/v1/attachments/{id}?exp=...&sig=... */
    public String signedPath(UUID id) {
        long exp = Instant.now().getEpochSecond() + LINK_SECONDS;
        return "/api/v1/attachments/" + id + "?exp=" + exp + "&sig=" + sign(id + ":" + exp);
    }

    /** Serves a file for a valid, unexpired signature; anything else looks like "not found". */
    public FileData open(UUID id, long exp, String sig) {
        if (sig == null || exp < Instant.now().getEpochSecond()) throw BusinessException.notFound("Attachment");
        if (!MessageDigest.isEqual(sign(id + ":" + exp).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
            throw BusinessException.notFound("Attachment");
        }
        return jdbc.sql("SELECT file_name, content_type, data FROM request_attachments WHERE id = :id AND deleted_at IS NULL")
                .param("id", id).query(FileData.class).optional().orElseThrow(() -> BusinessException.notFound("Attachment"));
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign attachment link", e);
        }
    }

    static String sniff(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        return null;
    }

    private static String safeName(String original, String type) {
        String ext = switch (type) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".pdf";
        };
        String base = original == null ? "attachment" : original.replaceAll("\\.[A-Za-z0-9]{1,5}$", "").replaceAll("[^A-Za-z0-9 _\\-]", "").trim();
        if (base.isBlank()) base = "attachment";
        if (base.length() > 80) base = base.substring(0, 80);
        return base + ext;
    }
}
