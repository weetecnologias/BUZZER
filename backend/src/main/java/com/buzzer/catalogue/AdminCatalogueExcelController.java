package com.buzzer.catalogue;

import com.buzzer.common.ApiResponse;
import com.buzzer.common.ExcelSupport;
import com.buzzer.identity.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/catalogue/excel")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminCatalogueExcelController {
    private final CatalogueExcelService excel;

    public AdminCatalogueExcelController(CatalogueExcelService excel) {
        this.excel = excel;
    }

    /** prefill=true: the whole live catalogue (edit and re-upload). prefill=false: an empty template with one example row. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "true") boolean prefill) {
        return ExcelSupport.download(prefill ? "buzzer-catalogue.xlsx" : "buzzer-catalogue-template.xlsx", excel.template(prefill));
    }

    @PostMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam("file") MultipartFile file) {
        return ApiResponse.of(excel.preview(file));
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importFile(@RequestParam("file") MultipartFile file) {
        return ApiResponse.of(excel.apply(file, CurrentUser.id()));
    }
}
