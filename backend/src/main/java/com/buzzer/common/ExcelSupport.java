package com.buzzer.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers for .xlsx templates, uploads and exports (Apache POI). */
public final class ExcelSupport {
    public static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final int MAX_ROWS = 20_000;

    private ExcelSupport() {}

    public record SheetData(String name, List<String> headers, List<List<Object>> rows) {}

    /** Text that a spreadsheet would treat as a formula is prefixed with ' (CSV/Excel injection protection). */
    public static Object safe(Object v) {
        if (v instanceof String s && !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) return "'" + s;
        return v;
    }

    public static byte[] write(List<SheetData> sheets) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            bold.setColor(IndexedColors.WHITE.getIndex());
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            for (SheetData sd : sheets) {
                Sheet sheet = wb.createSheet(sd.name());
                Row hr = sheet.createRow(0);
                for (int c = 0; c < sd.headers().size(); c++) {
                    Cell cell = hr.createCell(c);
                    cell.setCellValue(sd.headers().get(c));
                    cell.setCellStyle(head);
                }
                int r = 1;
                for (List<Object> row : sd.rows()) {
                    Row xr = sheet.createRow(r++);
                    for (int c = 0; c < row.size(); c++) {
                        Object v = safe(row.get(c));
                        Cell cell = xr.createCell(c);
                        if (v == null) cell.setBlank();
                        else if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                        else if (v instanceof Boolean b) cell.setCellValue(b ? "Y" : "N");
                        else cell.setCellValue(String.valueOf(v));
                    }
                }
                sheet.createFreezePane(0, 1);
                for (int c = 0; c < sd.headers().size(); c++) sheet.setColumnWidth(c, Math.min(60, Math.max(12, sd.headers().get(c).length() + 4)) * 256);
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create spreadsheet", e);
        }
    }

    /** Reads the named sheet (or the first sheet) into header->value maps. Blank rows are skipped; row numbers are kept. */
    public static List<Map<String, String>> read(MultipartFile file, String sheetName) {
        if (file == null || file.isEmpty()) throw BusinessException.invalid("Choose an .xlsx file to upload");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.endsWith(".xlsx")) throw BusinessException.invalid("Only .xlsx files are supported - download the template and fill it in");
        if (file.getSize() > 5 * 1024 * 1024) throw BusinessException.invalid("File is larger than 5 MB");
        try (InputStream in = file.getInputStream(); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = sheetName != null && wb.getSheet(sheetName) != null ? wb.getSheet(sheetName) : wb.getSheetAt(findDataSheet(wb));
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(0);
            if (header == null) throw BusinessException.invalid("The sheet has no header row");
            List<String> headers = new ArrayList<>();
            for (Cell c : header) headers.add(fmt.formatCellValue(c).trim().toLowerCase().replace(' ', '_'));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                Map<String, String> m = new LinkedHashMap<>();
                boolean any = false;
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.getCell(c);
                    String v = cell == null ? "" : cell.getCellType() == CellType.FORMULA ? "" : fmt.formatCellValue(cell).trim();
                    if (v.startsWith("'")) v = v.substring(1);
                    if (!v.isEmpty()) any = true;
                    m.put(headers.get(c), v);
                }
                if (!any) continue;
                m.put("_row", String.valueOf(r + 1));
                rows.add(m);
                if (rows.size() > MAX_ROWS) throw BusinessException.invalid("Too many rows (max " + MAX_ROWS + ")");
            }
            return rows;
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) throw be;
            throw BusinessException.invalid("Could not read the spreadsheet. Make sure it is a valid .xlsx file.");
        }
    }

    private static int findDataSheet(Workbook wb) {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) if (!wb.getSheetName(i).equalsIgnoreCase("Instructions")) return i;
        return 0;
    }

    public static BigDecimal decimal(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return new BigDecimal(v.replace(",", "").replace("₹", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static ResponseEntity<byte[]> download(String fileName, byte[] bytes) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(XLSX).body(bytes);
    }
}
