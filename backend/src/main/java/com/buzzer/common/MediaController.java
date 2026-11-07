package com.buzzer.common;

import com.buzzer.identity.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Catalogue images uploaded from the admin panel. File type is checked from the file's own bytes (not the
 * name or declared type), size is capped at 2 MB, and images are served with long-lived caching.
 * The returned URL ("/api/v1/media/{id}") is what goes into the catalogue or the Excel "image_url" column.
 */
@RestController
public class MediaController {
    private final JdbcClient jdbc;

    public MediaController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/admin/media")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw BusinessException.invalid("Choose an image");
        if (file.getSize() > 2 * 1024 * 1024) throw BusinessException.invalid("Image must be 2 MB or smaller");
        byte[] data = file.getBytes();
        String type = sniff(data);
        if (type == null) throw BusinessException.invalid("Only PNG, JPEG or WebP images are allowed");
        String name = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._-]", "_");
        UUID id = jdbc.sql("""
                INSERT INTO media_assets(file_name, content_type, size_bytes, data, uploaded_by) VALUES (:n, :t, :s, :d, :u) RETURNING id""")
                .param("n", name.length() > 200 ? name.substring(0, 200) : name).param("t", type).param("s", data.length).param("d", data)
                .param("u", CurrentUser.id()).query(UUID.class).single();
        return ApiResponse.of(Map.of("id", id, "url", "/api/v1/media/" + id, "content_type", type, "size_bytes", data.length));
    }

    @GetMapping("/api/v1/admin/media")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.of(jdbc.sql("""
                SELECT id, file_name, content_type, size_bytes, created_at, '/api/v1/media/' || id AS url
                FROM media_assets ORDER BY created_at DESC LIMIT 200""").query().listOfRows());
    }

    @GetMapping("/api/v1/media/{id}")
    public ResponseEntity<byte[]> get(@PathVariable UUID id) {
        record Media(String contentType, byte[] data) {}
        Media m = jdbc.sql("SELECT content_type, data FROM media_assets WHERE id = :id").param("id", id).query(Media.class).optional()
                .orElseThrow(() -> BusinessException.notFound("Image"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(m.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(m.data());
    }

    static String sniff(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
}
