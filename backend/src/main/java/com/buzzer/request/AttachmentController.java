package com.buzzer.request;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Serves a request attachment only for a valid, unexpired signed link (issued to people allowed to see the file). */
@RestController
public class AttachmentController {
    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @GetMapping("/api/v1/attachments/{id}")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam long exp, @RequestParam String sig) {
        AttachmentService.FileData f = attachments.open(id, exp, sig);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(f.data());
    }
}
