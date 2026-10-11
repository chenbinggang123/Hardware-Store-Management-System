package com.example.demo.excel.render;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Shared XLSX rendering rules used by every workbook the system hands to users:
 * formula-injection escaping, header styling, sheet naming and column sizing.
 * Keeping them in one place guarantees that files produced from Excel tasks and
 * files produced by the agent look and behave the same.
 */
public final class WorkbookRenderSupport {
    public static final String XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final int MIN_COLUMN_WIDTH = 2560;
    public static final int MAX_COLUMN_WIDTH = 12000;
    public static final int MAX_SHEET_NAME = 31;

    private WorkbookRenderSupport() {
    }

    public static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    /** Text starting with a formula trigger is prefixed with an apostrophe so spreadsheets never execute it. */
    public static String safeText(String value) {
        String text = value == null ? "" : value;
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    public static void writeText(Cell cell, String value) {
        cell.setCellValue(safeText(value));
    }

    public static boolean isNumericHeader(String value) {
        String header = value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return header.matches(".*(数量|单价|价格|金额|合计|小计|库存|成本|售价).*?");
    }

    public static String uniqueSheetName(String value, Set<String> used) {
        String base = WorkbookUtil.createSafeSheetName(StringUtils.hasText(value) ? value : "表格");
        if (!StringUtils.hasText(base)) base = "表格";
        base = base.length() <= MAX_SHEET_NAME ? base : base.substring(0, MAX_SHEET_NAME);
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            String tail = "-" + suffix++;
            candidate = base.substring(0, Math.min(base.length(), MAX_SHEET_NAME - tail.length())) + tail;
        }
        return candidate;
    }

    /** Freeze the header row, add a filter over the data block and give every column a readable width. */
    public static void finishSheet(Sheet sheet, int columnCount, int lastRowIndex) {
        if (columnCount <= 0) return;
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, lastRowIndex), 0, columnCount - 1));
        for (int column = 0; column < columnCount; column++) {
            int width;
            try {
                sheet.autoSizeColumn(column);
                width = sheet.getColumnWidth(column) + 512;
            } catch (RuntimeException headlessFontFailure) {
                width = 4800;
            }
            sheet.setColumnWidth(column, Math.min(Math.max(width, MIN_COLUMN_WIDTH), MAX_COLUMN_WIDTH));
        }
    }

    public static String baseName(String value) {
        String name = StringUtils.hasText(value) ? value.replaceAll("[\\\\/:*?\"<>|]", "_") : "文件";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public static String limit(String value, int maxLength) {
        String text = StringUtils.hasText(value) ? value : "未知生成错误";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
