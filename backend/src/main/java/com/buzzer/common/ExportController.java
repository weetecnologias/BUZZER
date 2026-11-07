package com.buzzer.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns any admin table (already authorised and loaded by the admin panel) into an .xlsx file.
 * CSV and PDF exports are produced in the browser; Excel needs a real workbook, so it is built here.
 */
@RestController
public class ExportController {
    public record Column(@NotNull String key, @NotNull String label) {}

    public record ExportInput(@Size(max = 80) String title, @NotEmpty @Size(max = 60) List<Column> columns,
                              @NotNull @Size(max = 50000) List<Map<String, Object>> rows) {}

    @PostMapping("/api/v1/admin/export/xlsx")
    public ResponseEntity<byte[]> xlsx(@Valid @RequestBody ExportInput in) {
        List<String> headers = in.columns().stream().map(Column::label).toList();
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : in.rows()) {
            List<Object> row = new ArrayList<>();
            for (Column c : in.columns()) {
                Object v = r.get(c.key());
                row.add(v instanceof Map || v instanceof List ? String.valueOf(v) : v);
            }
            rows.add(row);
        }
        String title = in.title() == null || in.title().isBlank() ? "export" : in.title().replaceAll("[^A-Za-z0-9_-]", "-");
        String sheet = title.length() > 30 ? title.substring(0, 30) : title;
        return ExcelSupport.download(title + "-" + LocalDate.now() + ".xlsx",
                ExcelSupport.write(List.of(new ExcelSupport.SheetData(sheet, headers, rows))));
    }
}
