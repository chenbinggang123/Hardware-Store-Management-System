package com.example.demo.agent.workbook;

import com.example.demo.excel.render.WorkbookRenderSupport;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import java.time.LocalDate;
import java.util.Arrays;

/** Paper receipt is a linked presentation sheet; the rectangular detail sheet owns all inputs. */
final class ReceiptWorkbookRenderer {
    private ReceiptWorkbookRenderer() { }

    static void validate(AgentWorkbookService.PreparedSheet source) {
        for (String role : new String[]{"QUANTITY", "UNIT_PRICE", "AMOUNT"}) {
            if (role(source, role) < 0) throw new IllegalArgumentException("收据版式需要数量、单价、票面金额列；未识别值可留空");
        }
    }

    static void render(Workbook book, AgentWorkbookService.Prepared document) {
        var source = document.sheets().get(0);
        Sheet sheet = book.createSheet(AgentWorkbookSheets.RECEIPT);
        book.setSheetOrder(sheet.getSheetName(), 0);
        book.setActiveSheet(0);
        sheet.setDisplayGridlines(false);
        Styles styles = new Styles(book);
        int quantity = role(source, "QUANTITY"), price = role(source, "UNIT_PRICE"), amount = role(source, "AMOUNT");
        boolean digital = "程序开单".equals(info(document, "单据来源"));
        if (digital) amount = role(source, "CALCULATED_AMOUNT");
        int name = column(source, "项目", "商品名称", "品名", "名称", "商品"), unit = column(source, "单位");
        int specification = column(source, "规格", "规格型号", "型号"), remark = column(source, "备注");
        if (name < 0) name = source.columns().get(0).header().equals("序号") ? 1 : 0;
        String ref = "'" + source.name().replace("'", "''") + "'!";
        int count = source.rows().size(), slots = Math.max(7, count), end = 4 + slots;
        for (int r = 0; r < end + 3; r++) {
            Row row = sheet.createRow(r); row.setHeightInPoints(r < 3 ? 25 : 27);
            for (int c = 0; c < 8; c++) row.createCell(c).setCellStyle(styles.body);
        }
        sheet.getRow(0).setHeightInPoints(39);
        merge(sheet, 0, 0, 0, 2, info(document, "公司名称", "开票方", "供货单位"), styles.company);
        String documentTitle = info(document, "单据名称");
        merge(sheet, 0, 0, 3, 5, documentTitle.isBlank() ? "收款收据" : documentTitle, styles.title);
        merge(sheet, 0, 0, 6, 7, "单据编号：" + info(document, "单号", "票号", "单据编号"), styles.meta);
        merge(sheet, 1, 1, 0, 5, "电话：" + info(document, "电话", "联系电话")
                + (info(document, "地址").isBlank() ? "" : "    地址：" + info(document, "地址")), styles.meta);
        text(sheet, 1, 6, "日期", styles.meta);
        text(sheet, 1, 7, info(document, "日期", "单据日期"), styles.meta);
        // Keep dates as native dates when legible, rather than baking a date into the heading string.
        String date = info(document, "日期", "单据日期");
        try {
            Cell cell = sheet.getRow(1).getCell(7); cell.setCellValue(LocalDate.parse(date)); cell.setCellStyle(styles.date);
        } catch (RuntimeException ignored) { /* source text retained if not an ISO date */ }
        merge(sheet, 2, 2, 0, 2, "购货单位：" + info(document, "客户名称", "客户", "购货单位"), styles.meta);
        merge(sheet, 2, 2, 3, 5, "经手人：" + info(document, "经手人", "制单人"), styles.meta);
        merge(sheet, 2, 2, 6, 7, "备注：" + info(document, "备注"), styles.meta);
        sheet.getRow(0).setHeightInPoints(height(info(document,"公司名称","开票方","供货单位"),24,24,39));
        sheet.getRow(1).setHeightInPoints(height(sheet.getRow(1).getCell(0).getStringCellValue(),78,13,25));
        sheet.getRow(2).setHeightInPoints(Math.max(height(sheet.getRow(2).getCell(0).getStringCellValue(),50,13,25),height(sheet.getRow(2).getCell(6).getStringCellValue(),35,13,25)));
        String[] headings = {"序号", "商品名称", "规格", "单位", "数量", "单价（元）", "金额（元）", "备注"};
        for (int c = 0; c < 8; c++) text(sheet, 3, c, headings[c], styles.heading);
        for (int i = 0; i < count; i++) {
            int row = 4 + i, detailRow = 5 + i;
            sheet.getRow(row).getCell(0).setCellValue(i + 1);
            linked(sheet, row, 1, ref + address(name, detailRow), styles.body);
            if (specification >= 0) linked(sheet, row, 2, ref + address(specification, detailRow), styles.body);
            if (unit >= 0) linked(sheet, row, 3, ref + address(unit, detailRow), styles.body);
            linked(sheet, row, 4, ref + address(quantity, detailRow), styles.number);
            linked(sheet, row, 5, ref + address(price, detailRow), styles.money);
            linked(sheet, row, 6, ref + address(amount, detailRow), styles.money);
            if (remark >= 0) linked(sheet, row, 7, ref + address(remark, detailRow), styles.body);
            float rowHeight=height(source.rows().get(i).get(name).display(),25,14,27);
            if(specification>=0) rowHeight=Math.max(rowHeight,height(source.rows().get(i).get(specification).display(),14,14,27));
            if(unit>=0) rowHeight=Math.max(rowHeight,height(source.rows().get(i).get(unit).display(),5,14,27));
            if(remark>=0) rowHeight=Math.max(rowHeight,height(source.rows().get(i).get(remark).display(),19,14,27));
            sheet.getRow(row).setHeightInPoints(rowHeight);
        }
        String amountRange = ref + address(amount, 5) + ":" + address(amount, 4 + count);
        merge(sheet, end, end, 0, 3, "合计", styles.heading);
        formula(sheet, end, 6, "IF(COUNT(" + amountRange + ")=" + count + ",SUM(" + amountRange + "),\"\")", styles.total);
        text(sheet, end, 7, "人民币", styles.body);
        int calculated = role(source, "CALCULATED_AMOUNT");
        String calculatedRange = ref + address(calculated, 5) + ":" + address(calculated, 4 + count);
        merge(sheet, end + 1, end + 1, 0, 7, "", styles.small);
        formula(sheet, end + 1, 0, "\"核算合计：\"&IF(COUNT(" + calculatedRange + ")=" + count + ",TEXT(SUM(" + calculatedRange
                + "),\"0.00\")&\" 元\",\"待核对\")&\"" + (digital ? "；金额按数量与单价计算。本单尚未登记销售、欠款或出库。" : "；票面金额保留原值，待核对项见明细页。") + "\"", styles.small);
        merge(sheet, end + 2, end + 2, 0, 3, "经手人：" + info(document, "经手人", "制单人"), styles.meta);
        merge(sheet, end + 2, end + 2, 4, 7, "客户签收：", styles.meta);
        int[] widths = {6, 27, 16, 7, 9, 13, 14, 21};
        for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, widths[c] * 256);
        sheet.createFreezePane(0, 4); sheet.setRepeatingRows(new CellRangeAddress(0, 3, -1, -1));
        sheet.setFitToPage(true); sheet.setHorizontallyCenter(true);
        PrintSetup print = sheet.getPrintSetup(); print.setPaperSize(PrintSetup.A4_PAPERSIZE); print.setLandscape(true);
        print.setFitWidth((short) 1); print.setFitHeight((short) 0);
        sheet.setMargin(Sheet.LeftMargin, .3); sheet.setMargin(Sheet.RightMargin, .3);
        sheet.setMargin(Sheet.TopMargin, .35); sheet.setMargin(Sheet.BottomMargin, .35);
        book.setPrintArea(book.getSheetIndex(sheet), 0, 7, 0, end + 2);
        book.setForceFormulaRecalculation(true);
    }

    private static int role(AgentWorkbookService.PreparedSheet source, String role) {
        for (int i = 0; i < source.columns().size(); i++) if (source.columns().get(i).role().name().equals(role)) return i;
        return -1;
    }
    private static int column(AgentWorkbookService.PreparedSheet source, String... names) {
        for (int i = 0; i < source.columns().size(); i++) if (Arrays.asList(names).contains(source.columns().get(i).header())) return i;
        return -1;
    }
    private static String info(AgentWorkbookService.Prepared source, String... names) {
        return source.info().stream().filter(item -> Arrays.asList(names).contains(item.getLabel())).map(AgentWorkbookSpec.InfoEntry::getValue).findFirst().orElse("");
    }
    private static String address(int col, int row) { return CellReference.convertNumToColString(col) + row; }
    private static float height(String text,int capacity,int lineHeight,int minimum) {
        int units=text.codePoints().map(c->c>127?2:1).sum();
        return Math.min(230,Math.max(minimum,(float)Math.ceil(units/(double)capacity)*lineHeight+7));
    }
    private static void linked(Sheet sheet, int row, int col, String ref, CellStyle style) {
        formula(sheet, row, col, "IF(" + ref + "=\"\",\"\"," + ref + ")", style);
    }
    private static void formula(Sheet sheet, int row, int col, String value, CellStyle style) {
        Cell cell = sheet.getRow(row).getCell(col); cell.setCellFormula(value); cell.setCellStyle(style);
    }
    private static void text(Sheet sheet, int row, int col, String value, CellStyle style) {
        Cell cell = sheet.getRow(row).getCell(col); if (!value.isEmpty()) cell.setCellValue(WorkbookRenderSupport.safeText(value));
        cell.setCellStyle(style);
    }
    private static void merge(Sheet sheet, int top, int bottom, int left, int right, String value, CellStyle style) {
        for (int r = top; r <= bottom; r++) for (int c = left; c <= right; c++) sheet.getRow(r).getCell(c).setCellStyle(style);
        if (top != bottom || left != right) sheet.addMergedRegion(new CellRangeAddress(top, bottom, left, right));
        text(sheet, top, left, value, style);
    }
    private static final class Styles {
        final CellStyle body, company, title, heading, meta, date, number, money, total, small;
        Styles(Workbook book) {
            body = base(book, 11, false, true); company = base(book, 20, true, false); title = base(book, 19, true, false);
            heading = base(book, 11, true, true); meta = base(book, 10, false, false); meta.setAlignment(HorizontalAlignment.LEFT);
            date = book.createCellStyle(); date.cloneStyleFrom(meta); date.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            number = book.createCellStyle(); number.cloneStyleFrom(body); number.setDataFormat(book.createDataFormat().getFormat("General"));
            money = book.createCellStyle(); money.cloneStyleFrom(body); money.setDataFormat(book.createDataFormat().getFormat("0.00"));
            total = book.createCellStyle(); total.cloneStyleFrom(heading); total.setDataFormat(book.createDataFormat().getFormat("\"￥\"#,##0.00"));
            small = base(book, 9, false, false); small.setAlignment(HorizontalAlignment.LEFT);
        }
        private static CellStyle base(Workbook book, int size, boolean bold, boolean border) {
            CellStyle style = book.createCellStyle(); Font font = book.createFont(); font.setFontName("宋体"); font.setFontHeightInPoints((short) size); font.setBold(bold);
            style.setFont(font); style.setAlignment(HorizontalAlignment.CENTER); style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true);
            if (border) { style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN); style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN); }
            return style;
        }
    }
}
