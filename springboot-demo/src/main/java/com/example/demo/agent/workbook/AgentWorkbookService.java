package com.example.demo.agent.workbook;

import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.service.AgentConversationService;
import com.example.demo.agent.service.AgentOutputService;
import com.example.demo.agent.service.attachment.AttachmentStorage;
import com.example.demo.agent.workbook.AgentWorkbookSpec.ColumnSpec;
import com.example.demo.agent.workbook.AgentWorkbookSpec.InfoEntry;
import com.example.demo.agent.workbook.AgentWorkbookSpec.SheetSpec;
import com.example.demo.entity.OperationLog;
import com.example.demo.excel.entity.ExcelTaskOutput;
import com.example.demo.excel.render.WorkbookRenderSupport;
import com.example.demo.excel.repository.ExcelTaskOutputRepository;
import com.example.demo.repository.OperationLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a model-described table into a real, permanent XLSX file for the current user.
 *
 * <p>The model decides what goes into the table; this service enforces size limits, keeps text as
 * text (so order numbers keep their leading zeros), parses numbers deterministically, re-computes
 * quantity × unit price with {@link BigDecimal}, writes real SUM formulas, escapes formula injection
 * and records provenance. Nothing here touches products, inventory, prices or orders.
 */
@Service
public class AgentWorkbookService {
    public static final String OUTPUT_TYPE = "AGENT_XLSX";
    static final int MAX_SHEETS = 10;
    static final int MAX_COLUMNS = 50;
    static final int MAX_ROWS_PER_SHEET = 2000;
    static final int MAX_TOTAL_ROWS = 5000;
    static final int MAX_CELL_CHARS = 2000;
    static final int MAX_TITLE_CHARS = 80;
    static final int MAX_INFO_ENTRIES = 40;
    static final int MAX_SOURCE_LINES = 3000;
    static final int PREVIEW_ROWS = 5;
    static final String INFO_SHEET = AgentWorkbookSheets.INFO;
    static final String SOURCE_SHEET = AgentWorkbookSheets.SOURCE;
    static final String ABOUT_SHEET = AgentWorkbookSheets.ABOUT;
    static final String NOTE_COLUMN = AgentWorkbookSheets.NOTE_COLUMN;
    private static final String RETENTION = "永久保存，可在“我的文件”中随时删除";
    private static final String EFFECT = "只生成一份可编辑的 Excel 文件，不修改商品、库存、价格、订单或账本";
    private static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.005");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final String RENDER_REVISION = "receipt-v2-simple";
    private static final DateTimeFormatter MONTH_STAMP = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter TIME_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-M-d"), DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"), DateTimeFormatter.ofPattern("yyyy年M月d日"));

    private final ExcelTaskOutputRepository outputRepository;
    private final AgentAttachmentRepository attachmentRepository;
    private final AgentConversationService conversationService;
    private final AttachmentStorage storage;
    private final OperationLogRepository operationLogRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentWorkbookService(ExcelTaskOutputRepository outputRepository,
                                AgentAttachmentRepository attachmentRepository,
                                AgentConversationService conversationService,
                                AttachmentStorage storage,
                                OperationLogRepository operationLogRepository,
                                ObjectMapper objectMapper) {
        this.outputRepository = outputRepository;
        this.attachmentRepository = attachmentRepository;
        this.conversationService = conversationService;
        this.storage = storage;
        this.operationLogRepository = operationLogRepository;
        this.objectMapper = objectMapper;
    }

    public AgentWorkbookService(ExcelTaskOutputRepository outputRepository,
                                AgentAttachmentRepository attachmentRepository,
                                AttachmentStorage storage,
                                ObjectMapper objectMapper) {
        this(outputRepository, attachmentRepository, null, storage, null, objectMapper);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long runId, Long operatorId, AgentWorkbookSpec spec) {
        return previewView(prepare(runId, operatorId, spec));
    }

    @Transactional
    public Map<String, Object> generate(Long runId, Long operatorId, AgentWorkbookSpec spec) {
        Prepared prepared = prepare(runId, operatorId, spec);
        String digest = digest(spec);
        String idempotencyKey = "agent-xlsx-" + runId + "-" + RENDER_REVISION + "-" + digest.substring(0, 16);
        Optional<ExcelTaskOutput> prior = outputRepository.findByOperatorIdAndIdempotencyKey(operatorId, idempotencyKey);
        if (prior.isPresent()) {
            if (!digest.equals(prior.get().getRequestDigest())) {
                throw new IllegalArgumentException("同一任务中已用相同标识生成过不同内容的文件");
            }
            return resultView(prior.get(), prepared);
        }
        LocalDateTime now = LocalDateTime.now();
        byte[] bytes = render(prepared, now, operatorId, runId);

        ExcelTaskOutput output = new ExcelTaskOutput();
        output.setTaskId(null);
        output.setOperatorId(operatorId);
        output.setSourceType(AgentOutputService.AGENT_SOURCE_TYPE);
        output.setSourceId(prepared.attachment == null ? null : prepared.attachment.getId());
        output.setRunId(runId);
        output.setConversationId(prepared.conversationId);
        output.setOutputType(OUTPUT_TYPE);
        output.setOriginalName(prepared.fileName(now));
        output.setMimeType(WorkbookRenderSupport.XLSX_MIME);
        output.setFileSize((long) bytes.length);
        output.setSha256(WorkbookRenderSupport.digest(bytes));
        output.setStatus("GENERATING");
        output.setRequestDigest(digest);
        output.setIdempotencyKey(idempotencyKey);
        output.setSummaryJson(toJson(summary(prepared)));
        output.setCreateTime(now);
        output.setExpiresAt(null);
        output = outputRepository.saveAndFlush(output);

        String objectKey = "agent-outputs/" + operatorId + "/" + now.format(MONTH_STAMP) + "/" + output.getId()
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".xlsx";
        try {
            storage.put(objectKey, bytes, WorkbookRenderSupport.XLSX_MIME);
            if (storage.stat(objectKey).size() != bytes.length) {
                throw new IllegalStateException("保存后的 Excel 文件大小校验失败");
            }
        } catch (RuntimeException exception) {
            output.setStatus("FAILED");
            output.setErrorMessage(WorkbookRenderSupport.limit(exception.getMessage(), 1000));
            outputRepository.save(output);
            throw new IllegalStateException("Excel 文件保存失败：" + exception.getMessage(), exception);
        }
        output.setObjectKey(objectKey);
        output.setStatus("READY");
        output = outputRepository.save(output);
        audit(operatorId, "GENERATE", "小五生成 Excel 文件：" + output.getOriginalName() + "（#" + output.getId()
                + "，" + prepared.totalRows + " 行）");
        return resultView(output, prepared);
    }

    // ---------------------------------------------------------------- preparation

    Prepared prepare(Long runId, Long operatorId, AgentWorkbookSpec spec) {
        if (spec == null) throw new IllegalArgumentException("Excel 内容不能为空");
        if (operatorId == null) throw new IllegalArgumentException("缺少有效操作人");
        List<String> warnings = new ArrayList<>();
        String title = cleanTitle(spec.getTitle());
        List<SheetSpec> sheetSpecs = spec.getSheets() == null ? List.of()
                : spec.getSheets().stream().filter(Objects::nonNull).toList();
        if (sheetSpecs.isEmpty()) throw new IllegalArgumentException("至少需要一个带表头和数据行的工作表");
        if (sheetSpecs.size() > MAX_SHEETS) throw new IllegalArgumentException("工作表不能超过 " + MAX_SHEETS + " 个");

        String layout = StringUtils.hasText(spec.getLayout()) ? spec.getLayout().trim().toUpperCase(Locale.ROOT) : "TABLE";
        if (!Set.of("TABLE", "RECEIPT").contains(layout)) throw new IllegalArgumentException("不支持的单据版式：" + layout);
        Set<String> usedNames = new HashSet<>(List.of(INFO_SHEET, SOURCE_SHEET, ABOUT_SHEET, AgentWorkbookSheets.RECEIPT));
        List<PreparedSheet> sheets = new ArrayList<>();
        int totalRows = 0;
        int mismatches = 0;
        int index = 0;
        for (SheetSpec sheetSpec : sheetSpecs) {
            PreparedSheet sheet = prepareSheet(sheetSpec, ++index, usedNames, warnings);
            totalRows += sheet.rows.size();
            mismatches += sheet.mismatchCount;
            if (totalRows > MAX_TOTAL_ROWS) {
                throw new IllegalArgumentException("所有工作表合计不能超过 " + MAX_TOTAL_ROWS + " 行数据");
            }
            sheets.add(sheet);
        }
        if (totalRows == 0) throw new IllegalArgumentException("没有可写入的数据行，请先整理出表格内容");
        if (layout.equals("RECEIPT")) ReceiptWorkbookRenderer.validate(sheets.get(0));

        Long conversationId = conversationService == null || runId == null ? null
                : conversationService.conversationIdForRun(runId);
        AgentAttachment attachment = resolveAttachment(runId, operatorId, conversationId, spec.getAttachmentId(), warnings);
        boolean wantsSource = Boolean.TRUE.equals(spec.getIncludeSourceText());
        boolean includeSource = wantsSource && attachment != null && StringUtils.hasText(attachment.getExtractedText());
        if (wantsSource && !includeSource) warnings.add("没有可附带的附件识别原文，已跳过“附件原文”工作表");
        List<InfoEntry> info = cleanInfo(spec.getInfo(), warnings);
        if (title.equals("单据明细")) {
            String party = infoValue(info, "开票方", "供货单位", "供应商", "公司名称");
            String number = infoValue(info, "单号", "订单号", "单据编号", "票号");
            if (!party.equals("未提供")) title = cleanTitle(truncate(party, 28) + "_单据明细");
            if (!number.equals("未提供")) title = cleanTitle(title + "_" + number);
        }
        for (PreparedSheet sheet : sheets) {
            int calculated = indexOfRole(sheet.columns, ColumnRole.CALCULATED_AMOUNT);
            if (calculated >= 0 && sheet.rows.stream().anyMatch(row -> row.get(calculated).number == null))
                warnings.add("工作表“" + sheet.name + "”有行缺少可核算数据；核算合计仅汇总可计算行，不代表完整票面总额");
        }
        if (sheets.size() == 1) {
            PreparedSheet sheet = sheets.get(0);
            int calculated = indexOfRole(sheet.columns, ColumnRole.CALCULATED_AMOUNT);
            if (calculated >= 0 && sheet.rows.stream().allMatch(row -> row.get(calculated).number != null)) {
                BigDecimal sum = sheet.rows.stream().map(row -> row.get(calculated).number).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
                for (InfoEntry entry : info) {
                    if (!entry.getLabel().matches(".*(票面合计|票面总额|票面总计).*")) continue;
                    BigDecimal printed = parseNumber(entry.getValue());
                    if (printed == null && entry.getValue() != null) {
                        var matcher = java.util.regex.Pattern.compile("[￥¥]\\s*[:：]?\\s*([0-9,]+(?:\\.[0-9]+)?)").matcher(entry.getValue());
                        if (matcher.find()) printed = parseNumber(matcher.group(1));
                    }
                    if (printed != null && printed.subtract(sum).abs().compareTo(AMOUNT_TOLERANCE) > 0)
                        warnings.add("生成时核算合计 " + sum.toPlainString() + "；票面合计原文中的数值 " + printed.setScale(2, RoundingMode.HALF_UP).toPlainString()
                                + "；相差 " + printed.subtract(sum).abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + "，请核对原单，系统未调整任何源数据");
                }
            }
        }
        return new Prepared(title, layout, sheets, info, attachment, conversationId, includeSource,
                cleanNote(spec.getNote()), warnings, totalRows, mismatches);
    }

    private PreparedSheet prepareSheet(SheetSpec spec, int index, Set<String> usedNames, List<String> warnings) {
        List<ColumnSpec> columnSpecs = spec.getColumns() == null ? List.of()
                : spec.getColumns().stream().filter(Objects::nonNull).toList();
        if (columnSpecs.isEmpty()) throw new IllegalArgumentException("第 " + index + " 个工作表缺少表头");
        if (columnSpecs.size() > MAX_COLUMNS) throw new IllegalArgumentException("单个工作表列数不能超过 " + MAX_COLUMNS);
        String name = WorkbookRenderSupport.uniqueSheetName(spec.getName(), usedNames);

        List<PreparedColumn> columns = new ArrayList<>();
        Set<String> headerNames = new HashSet<>();
        for (int column = 0; column < columnSpecs.size(); column++) {
            ColumnSpec columnSpec = columnSpecs.get(column);
            String header = StringUtils.hasText(columnSpec.getName())
                    ? truncate(columnSpec.getName().trim(), 100) : "列" + (column + 1);
            String base = header;
            int suffix = 2;
            while (!headerNames.add(header)) header = base + "-" + suffix++;
            ColumnRole role = ColumnRole.parse(columnSpec.getRole());
            if (header.matches(".*(核算金额|计算金额).*")) role = ColumnRole.CALCULATED_AMOUNT;
            else if (role == ColumnRole.NONE && header.matches("^(数量|件数|数目)$")) role = ColumnRole.QUANTITY;
            else if (role == ColumnRole.NONE && header.matches("^(单价|折前单价|含税单价)$")) role = ColumnRole.UNIT_PRICE;
            else if (role == ColumnRole.NONE && header.matches("^(金额|票面金额|折后金额|小计)$")) role = ColumnRole.AMOUNT;
            columns.add(new PreparedColumn(header, role == ColumnRole.CALCULATED_AMOUNT ? ColumnType.MONEY : ColumnType.parse(columnSpec.getType(), role, header), role));
        }
        int quantityIndex = indexOfRole(columns, ColumnRole.QUANTITY);
        int unitPriceIndex = indexOfRole(columns, ColumnRole.UNIT_PRICE);
        int amountIndex = indexOfRole(columns, ColumnRole.AMOUNT);
        boolean verify = quantityIndex >= 0 && unitPriceIndex >= 0;
        boolean addLineNumber = verify && columns.stream().noneMatch(column -> column.header.matches("^(行号|序号)$"));
        if (addLineNumber) {
            if (columns.size() >= MAX_COLUMNS - 1) throw new IllegalArgumentException("请为序号及核算金额预留两列");
            columns.add(0, new PreparedColumn("序号", ColumnType.INTEGER, ColumnRole.NONE));
            quantityIndex++; unitPriceIndex++; if (amountIndex >= 0) amountIndex++;
        }
        int calculatedIndex = indexOfRole(columns, ColumnRole.CALCULATED_AMOUNT);
        if (verify && calculatedIndex < 0) {
            if (columns.size() >= MAX_COLUMNS) throw new IllegalArgumentException("需要为核算金额预留一列，原始字段不能超过 " + (MAX_COLUMNS - 1) + " 列");
            calculatedIndex = columns.size();
            columns.add(new PreparedColumn("核算金额", ColumnType.MONEY, ColumnRole.CALCULATED_AMOUNT));
        }
        boolean hasPricingAdjustments = columns.stream().anyMatch(column -> column.header.matches(".*(折扣|优惠|税率|运费).*"));

        List<Object> rawRows = spec.getRows() == null ? List.of() : spec.getRows();
        if (rawRows.size() > MAX_ROWS_PER_SHEET) {
            throw new IllegalArgumentException("工作表“" + name + "”超过 " + MAX_ROWS_PER_SHEET + " 行");
        }
        List<List<PreparedCell>> rows = new ArrayList<>();
        List<String> rowNotes = new ArrayList<>();
        int mismatchCount = 0;
        int truncatedCells = 0;
        int nonNumericCells = 0;
        for (Object raw : rawRows) {
            List<Object> values = rowValues(raw, columns);
            if (values.stream().allMatch(value -> value == null || !StringUtils.hasText(String.valueOf(value)))) continue;
            if (addLineNumber) {
                if (raw instanceof List<?>) values.add(0, rows.size() + 1);
                else if (!values.isEmpty()) values.set(0, rows.size() + 1);
            }
            List<PreparedCell> cells = new ArrayList<>();
            List<String> notes = new ArrayList<>();
            for (int column = 0; column < columns.size(); column++) {
                PreparedCell cell = toCell(column < values.size() ? values.get(column) : null, columns.get(column));
                if (cell.truncated) truncatedCells++;
                if (cell.nonNumeric) {
                    nonNumericCells++;
                    notes.add(columns.get(column).header + "“" + abbreviate(cell.text, 20) + "”不是数字，按文本保留");
                }
                cells.add(cell);
            }
            if (verify) {
                BigDecimal quantity = cells.get(quantityIndex).number;
                BigDecimal unitPrice = cells.get(unitPriceIndex).number;
                PreparedCell amount = amountIndex >= 0 ? cells.get(amountIndex) : PreparedCell.empty();
                if (hasPricingAdjustments) {
                    cells.set(calculatedIndex, PreparedCell.empty());
                    notes.add("含折扣、优惠或附加费用字段，计价规则需确认，未自动核算");
                } else if (quantity != null && unitPrice != null) {
                    BigDecimal expected = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
                    cells.set(calculatedIndex, PreparedCell.number(expected, false));
                    if (amount.number == null && !StringUtils.hasText(amount.text)) {
                        notes.add("票面金额未提供；仅计算核算金额，不代表原单金额");
                    } else if (amount.number != null && amount.number.subtract(expected).abs().compareTo(AMOUNT_TOLERANCE) > 0) {
                        cells.set(amountIndex, PreparedCell.number(amount.number, true));
                        mismatchCount++;
                        notes.add("生成时金额验算：" + plain(quantity) + "×" + plain(unitPrice) + "=" + expected.toPlainString()
                                + "，与填写值 " + plain(amount.number) + " 不一致，请核对");
                    }
                } else if (amount.number != null) {
                    cells.set(calculatedIndex, PreparedCell.empty());
                    notes.add("数量或单价缺失，金额未验算");
                } else {
                    cells.set(calculatedIndex, PreparedCell.empty());
                    notes.add("数量或单价缺失，核算金额留空，需人工核对");
                }
            }
            rows.add(cells);
            rowNotes.add(String.join("；", notes));
        }
        if (truncatedCells > 0) warnings.add("工作表“" + name + "”有 " + truncatedCells + " 个单元格超过 " + MAX_CELL_CHARS + " 字，已截断");
        if (nonNumericCells > 0) warnings.add("工作表“" + name + "”有 " + nonNumericCells + " 个数字列单元格不是数字，已按文本保留并写入核对说明");
        if (mismatchCount > 0) warnings.add("工作表“" + name + "”有 " + mismatchCount + " 行金额与数量×单价不一致，已标黄并写入核对说明");

        LinkedHashSet<Integer> totals = new LinkedHashSet<>();
        for (String total : spec.getTotalColumns() == null ? List.<String>of() : spec.getTotalColumns()) {
            int found = indexOfHeader(columns, total);
            if (found < 0) warnings.add("工作表“" + name + "”找不到求和列“" + total + "”，已忽略");
            else if (!columns.get(found).type.numeric()) warnings.add("列“" + columns.get(found).header + "”不是数字列，不能求和");
            else totals.add(found);
        }
        if (amountIndex >= 0 && columns.get(amountIndex).type.numeric()) totals.add(amountIndex);
        if (calculatedIndex >= 0) totals.add(calculatedIndex);
        boolean hasNotes = rowNotes.stream().anyMatch(StringUtils::hasText);
        return new PreparedSheet(name, columns, rows, rowNotes, hasNotes, new ArrayList<>(totals), mismatchCount,
                cleanNote(spec.getNote()));
    }

    private List<Object> rowValues(Object raw, List<PreparedColumn> columns) {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) return new ArrayList<>(list);
        if (raw instanceof Map<?, ?> map) {
            List<Object> values = new ArrayList<>();
            for (PreparedColumn column : columns) values.add(map.get(column.header));
            return values;
        }
        return new ArrayList<>(List.of(raw));
    }

    private PreparedCell toCell(Object value, PreparedColumn column) {
        if (value == null) return PreparedCell.empty();
        if (value instanceof Number number && column.type.numeric()) return PreparedCell.number(toDecimal(number), false);
        String text = value instanceof Number number ? plain(toDecimal(number)) : String.valueOf(value).trim();
        boolean truncated = false;
        if (text.length() > MAX_CELL_CHARS) {
            text = text.substring(0, MAX_CELL_CHARS);
            truncated = true;
        }
        if (!StringUtils.hasText(text)) return PreparedCell.empty();
        if (column.type == ColumnType.DATE) {
            LocalDate date = parseDate(text);
            return date != null ? PreparedCell.date(date) : PreparedCell.text(text, truncated, false);
        }
        if (column.type.numeric()) {
            BigDecimal number = parseNumber(text);
            return number != null ? PreparedCell.number(number, false) : PreparedCell.text(text, truncated, true);
        }
        return PreparedCell.text(text, truncated, false);
    }

    private AgentAttachment resolveAttachment(Long runId, Long operatorId, Long conversationId, Long requestedId,
                                              List<String> warnings) {
        if (requestedId != null) {
            List<AgentAttachment> found = attachmentRepository.findByIdInAndOperatorId(List.of(requestedId), operatorId);
            if (found.isEmpty()) {
                warnings.add("附件 " + requestedId + " 不存在或不属于当前用户，已忽略来源附件");
                return null;
            }
            AgentAttachment attachment = found.get(0);
            if (conversationId != null && attachment.getConversationId() != null
                    && !conversationId.equals(attachment.getConversationId())) {
                warnings.add("附件 " + requestedId + " 不属于当前会话，已忽略来源附件");
                return null;
            }
            return attachment;
        }
        if (runId == null) return null;
        List<AgentAttachment> current = attachmentRepository.findByRunIdOrderByCreateTimeAsc(runId).stream()
                .filter(item -> operatorId.equals(item.getOperatorId())).toList();
        return current.size() == 1 ? current.get(0) : null;
    }

    private List<InfoEntry> cleanInfo(List<InfoEntry> entries, List<String> warnings) {
        if (entries == null) return List.of();
        List<InfoEntry> result = new ArrayList<>();
        for (InfoEntry entry : entries) {
            if (entry == null || !StringUtils.hasText(entry.getLabel())) continue;
            if (result.size() >= MAX_INFO_ENTRIES) {
                warnings.add("单据信息超过 " + MAX_INFO_ENTRIES + " 项，多余部分已忽略");
                break;
            }
            result.add(new InfoEntry(truncate(entry.getLabel().trim(), 100),
                    entry.getValue() == null ? "" : truncate(entry.getValue().trim(), MAX_CELL_CHARS)));
        }
        return result;
    }

    // ---------------------------------------------------------------- rendering

    byte[] render(Prepared prepared, LocalDateTime now, Long operatorId, Long runId) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            Styles styles = new Styles(workbook);
            for (PreparedSheet sheet : prepared.sheets) renderSheet(workbook, styles, sheet, prepared);
            if (prepared.layout.equals("RECEIPT")) ReceiptWorkbookRenderer.render(workbook, prepared);
            if (!prepared.info.isEmpty()) renderInfo(workbook, styles, prepared.info);
            if (prepared.includeSourceText) renderSource(workbook, styles, prepared.attachment);
            renderAbout(workbook, styles, prepared, now, operatorId, runId);
            XSSFFormulaEvaluator.evaluateAllFormulaCells(workbook);
            workbook.write(buffer);
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Excel 文件生成失败", exception);
        }
    }

    private void renderSheet(Workbook workbook, Styles styles, PreparedSheet prepared, Prepared document) {
        Sheet sheet = workbook.createSheet(prepared.name);
        int columnCount = prepared.columns.size() + (prepared.hasNotes ? 1 : 0);
        boolean order = indexOfRole(prepared.columns, ColumnRole.QUANTITY) >= 0 && indexOfRole(prepared.columns, ColumnRole.UNIT_PRICE) >= 0;
        int headerIndex = order ? 3 : 0;
        if (order) {
            mergedLine(sheet, 0, columnCount, document.title.replace('_', ' '), styles.title, 34);
            mergedLine(sheet, 1, columnCount, "单据编号：" + infoValue(document.info, "单号", "订单号", "单据编号", "票号")
                    + "                 单据日期：" + infoValue(document.info, "日期", "单据日期", "开票日期", "订单日期"), styles.meta, 24);
            mergedLine(sheet, 2, columnCount, "供货单位：" + infoValue(document.info, "开票方", "供货单位", "供应商", "公司名称")
                    + "                 购货单位：" + infoValue(document.info, "购货单位", "客户", "客户名称", "购买单位"), styles.meta, 26);
        }
        Row header = sheet.createRow(headerIndex);
        header.setHeightInPoints(27);
        for (int column = 0; column < prepared.columns.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(WorkbookRenderSupport.safeText(displayHeader(prepared.columns.get(column))));
            cell.setCellStyle(styles.header);
        }
        if (prepared.hasNotes) {
            Cell cell = header.createCell(prepared.columns.size());
            cell.setCellValue(NOTE_COLUMN);
            cell.setCellStyle(styles.header);
        }
        int rowIndex = headerIndex + 1;
        int quantityIndex = indexOfRole(prepared.columns, ColumnRole.QUANTITY);
        int priceIndex = indexOfRole(prepared.columns, ColumnRole.UNIT_PRICE);
        boolean adjusted = prepared.columns.stream().anyMatch(column -> column.header.matches(".*(折扣|优惠|税率|运费).*"));
        for (int row = 0; row < prepared.rows.size(); row++) {
            Row target = sheet.createRow(rowIndex++);
            target.setHeightInPoints(prepared.hasNotes ? 42 : 24);
            List<PreparedCell> cells = prepared.rows.get(row);
            for (int column = 0; column < prepared.columns.size(); column++) {
                Cell cell = target.createCell(column);
                if (prepared.columns.get(column).role == ColumnRole.CALCULATED_AMOUNT && quantityIndex >= 0 && priceIndex >= 0 && !adjusted) {
                    String q = CellReference.convertNumToColString(quantityIndex) + rowIndex;
                    String p = CellReference.convertNumToColString(priceIndex) + rowIndex;
                    cell.setCellFormula("IF(AND(ISNUMBER(" + q + "),ISNUMBER(" + p + ")),ROUND(" + q + "*" + p + ",2),\"\")");
                    cell.setCellStyle(styles.number(ColumnType.MONEY, false));
                } else writeCell(cell, cells.get(column), prepared.columns.get(column), styles);
            }
            if (prepared.hasNotes) {
                String note = prepared.rowNotes.get(row);
                Cell cell = target.createCell(prepared.columns.size());
                cell.setCellValue(WorkbookRenderSupport.safeText(note));
                cell.setCellStyle(StringUtils.hasText(note) ? styles.flaggedText : styles.text);
            }
        }
        int lastDataRow = rowIndex - 1;
        if (!prepared.totalColumns.isEmpty() && !prepared.rows.isEmpty()) {
            Row total = sheet.createRow(rowIndex++);
            total.setHeightInPoints(26);
            for (int col = 0; col < columnCount; col++) total.createCell(col).setCellStyle(styles.totalText);
            if (!prepared.totalColumns.contains(0)) {
                Cell label = total.createCell(0);
                label.setCellValue(AgentWorkbookSheets.TOTAL_LABEL);
                label.setCellStyle(styles.totalText);
            }
            for (int column : prepared.totalColumns) {
                String letter = CellReference.convertNumToColString(column);
                Cell cell = total.createCell(column);
                String range = letter + (headerIndex + 2) + ":" + letter + (lastDataRow + 1);
                cell.setCellFormula("IF(COUNT(" + range + ")=0,\"\",SUM(" + range + "))");
                cell.setCellStyle(styles.total(prepared.columns.get(column).type));
            }
        }
        if (StringUtils.hasText(prepared.note)) {
            mergedLine(sheet, rowIndex++, columnCount, "说明：" + prepared.note, styles.meta, 30);
        }
        if (order) {
            mergedLine(sheet, rowIndex++, columnCount, adjusted ? "说明：计价调整规则待确认，核算金额留空；票面金额按原文保留。"
                    : "说明：核算金额随数量、单价修改自动重算；票面金额按原文保留。核对说明为生成时记录，改动后需重新核对。", styles.meta, 30);
            for (InfoEntry entry : document.info) {
                if (entry.getLabel().contains("合计") || entry.getLabel().contains("识别说明"))
                    mergedLine(sheet, rowIndex++, columnCount, "说明：" + entry.getLabel() + "：" + entry.getValue(), styles.meta, 28);
            }
            for (String warning : document.warnings) mergedLine(sheet, rowIndex++, columnCount, "说明：待核对：" + warning, styles.meta, 30);
        }
        WorkbookRenderSupport.finishSheet(sheet, columnCount, lastDataRow);
        for (int col = 0; col < columnCount; col++) {
            String label = col < prepared.columns.size() ? prepared.columns.get(col).header : NOTE_COLUMN;
            if (label.contains("说明") || label.contains("备注")) sheet.setColumnWidth(col, 11000);
            else if (label.matches(".*(项目|商品|名称|品名).*")) sheet.setColumnWidth(col, 6500);
            else if (label.contains("单位")) sheet.setColumnWidth(col, 2600);
            else if (label.contains("金额") || label.contains("单价")) sheet.setColumnWidth(col, 3600);
        }
        sheet.setDisplayGridlines(false);
        sheet.createFreezePane(0, headerIndex + 1);
        sheet.setAutoFilter(new CellRangeAddress(headerIndex, Math.max(headerIndex, lastDataRow), 0, columnCount - 1));
        sheet.setRepeatingRows(new CellRangeAddress(0, headerIndex, -1, -1));
        sheet.setFitToPage(true); sheet.setAutobreaks(true);
        sheet.getPrintSetup().setLandscape(true); sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setFitWidth((short) 1); sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setMargin(Sheet.LeftMargin, 0.25); sheet.setMargin(Sheet.RightMargin, 0.25);
        sheet.setMargin(Sheet.TopMargin, 0.35); sheet.setMargin(Sheet.BottomMargin, 0.35);
        workbook.setPrintArea(workbook.getSheetIndex(sheet), 0, columnCount - 1, 0, sheet.getLastRowNum());
    }

    private static String displayHeader(PreparedColumn column) {
        return column.role == ColumnRole.AMOUNT && column.header.equals("金额") ? "票面金额" : column.header;
    }

    private static String infoValue(List<InfoEntry> info, String... labels) {
        for (String label : labels) for (InfoEntry item : info)
            if (label.equals(item.getLabel()) && StringUtils.hasText(item.getValue())) return item.getValue();
        return "未提供";
    }

    private void mergedLine(Sheet sheet, int index, int columns, String value, CellStyle style, float height) {
        Row row = sheet.createRow(index); row.setHeightInPoints(height);
        Cell cell = row.createCell(0); cell.setCellValue(WorkbookRenderSupport.safeText(value)); cell.setCellStyle(style);
        if (columns > 1) sheet.addMergedRegion(new CellRangeAddress(index, index, 0, columns - 1));
    }

    private void writeCell(Cell cell, PreparedCell value, PreparedColumn column, Styles styles) {
        if (value.number != null) {
            cell.setCellValue(value.number.doubleValue());
            cell.setCellStyle(styles.number(column.type, value.flagged));
            return;
        }
        if (value.date != null) {
            cell.setCellValue(value.date);
            cell.setCellStyle(styles.date(value.flagged));
            return;
        }
        if (value.text == null || value.text.isEmpty()) cell.setBlank();
        else cell.setCellValue(WorkbookRenderSupport.safeText(value.text));
        cell.setCellStyle(value.flagged || value.nonNumeric ? styles.flaggedText : styles.text);
    }

    private void renderInfo(Workbook workbook, Styles styles, List<InfoEntry> info) {
        Sheet sheet = workbook.createSheet(INFO_SHEET);
        Row header = sheet.createRow(0);
        writeHeader(header, 0, "项目", styles);
        writeHeader(header, 1, "内容", styles);
        int rowIndex = 1;
        for (InfoEntry entry : info) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(WorkbookRenderSupport.safeText(entry.getLabel()));
            row.createCell(1).setCellValue(WorkbookRenderSupport.safeText(entry.getValue()));
            if (entry.getValue().isEmpty()) row.getCell(1).setBlank();
            row.getCell(0).setCellStyle(styles.text); row.getCell(1).setCellStyle(styles.longText);
            row.setHeightInPoints(Math.max(28, Math.min(140, 14 * (float)Math.ceil(entry.getValue().length() / 32.0) + 8)));
        }
        WorkbookRenderSupport.finishSheet(sheet, 2, rowIndex - 1);
        sheet.setColumnWidth(0, 6000); sheet.setColumnWidth(1, 19000);
    }

    private void renderSource(Workbook workbook, Styles styles, AgentAttachment attachment) {
        Sheet sheet = workbook.createSheet(SOURCE_SHEET);
        Row header = sheet.createRow(0);
        writeHeader(header, 0, "识别原文（" + attachment.getOriginalName() + "）", styles);
        int rowIndex = 1;
        for (String line : attachment.getExtractedText().split("\\r?\\n")) {
            if (rowIndex > MAX_SOURCE_LINES) {
                sheet.createRow(rowIndex).createCell(0).setCellValue("[原文过长，已截断]");
                break;
            }
            sheet.createRow(rowIndex++).createCell(0)
                    .setCellValue(WorkbookRenderSupport.safeText(truncate(line, MAX_CELL_CHARS)));
        }
        sheet.createFreezePane(0, 1);
        sheet.setColumnWidth(0, 24000);
    }

    private void renderAbout(Workbook workbook, Styles styles, Prepared prepared, LocalDateTime now,
                             Long operatorId, Long runId) {
        Sheet sheet = workbook.createSheet(ABOUT_SHEET);
        Row header = sheet.createRow(0);
        writeHeader(header, 0, "项目", styles);
        writeHeader(header, 1, "内容", styles);
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"文件标题", prepared.title});
        lines.add(new String[] {"生成时间", now.format(TIME_STAMP)});
        lines.add(new String[] {"生成方式", "按对话中整理的内容生成；待核对项以明细及说明为准"});
        lines.add(new String[] {"操作人 ID", String.valueOf(operatorId)});
        lines.add(new String[] {"Agent 任务 ID", runId == null ? "" : String.valueOf(runId)});
        if (prepared.attachment != null) {
            lines.add(new String[] {"来源附件", prepared.attachment.getOriginalName() + "（#" + prepared.attachment.getId() + "）"});
            lines.add(new String[] {"附件 SHA-256", prepared.attachment.getSha256() == null ? "" : prepared.attachment.getSha256()});
            lines.add(new String[] {"附件识别状态", prepared.attachment.getParseStatus() == null ? "" : prepared.attachment.getParseStatus()});
        }
        lines.add(new String[] {"工作表", prepared.sheets.stream().map(item -> item.name + "（" + item.rows.size() + " 行）")
                .reduce((left, right) -> left + "、" + right).orElse("")});
        lines.add(new String[] {"数据行数", String.valueOf(prepared.totalRows)});
        lines.add(new String[] {"金额验算差异", String.valueOf(prepared.mismatchCount) + " 行（仅统计可逐行核对的票面金额；金额缺失不代表一致）"});
        if (!prepared.warnings.isEmpty()) lines.add(new String[] {"提示", String.join("；", prepared.warnings)});
        if (StringUtils.hasText(prepared.note)) lines.add(new String[] {"补充说明", prepared.note});
        lines.add(new String[] {"声明", "数量×单价的验算差异已在“" + NOTE_COLUMN + "”列标出；本文件不代表已写入商品、库存、价格或订单，数据请以原始单据为准。"});
        int rowIndex = 1;
        for (String[] line : lines) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(WorkbookRenderSupport.safeText(line[0]));
            row.createCell(1).setCellValue(WorkbookRenderSupport.safeText(line[1]));
            if (line[1].isEmpty()) row.getCell(1).setBlank();
            row.getCell(0).setCellStyle(styles.text); row.getCell(1).setCellStyle(styles.longText);
            row.setHeightInPoints(Math.max(28, Math.min(140, 14 * (float)Math.ceil(line[1].length() / 32.0) + 8)));
        }
        WorkbookRenderSupport.finishSheet(sheet, 2, rowIndex - 1);
        sheet.setColumnWidth(0, 6000); sheet.setColumnWidth(1, 19000);
    }

    private void writeHeader(Row row, int column, String text, Styles styles) {
        Cell cell = row.createCell(column);
        cell.setCellValue(WorkbookRenderSupport.safeText(text));
        cell.setCellStyle(styles.header);
    }

    // ---------------------------------------------------------------- views

    Map<String, Object> previewView(Prepared prepared) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", "EXCEL_WORKBOOK");
        view.put("title", prepared.title);
        view.put("layout", prepared.layout);
        view.put("fileName", prepared.fileName(LocalDateTime.now()));
        view.put("sheetCount", prepared.sheets.size());
        view.put("rowCount", prepared.totalRows);
        view.put("mismatchCount", prepared.mismatchCount);
        List<Map<String, Object>> sheets = new ArrayList<>();
        for (PreparedSheet sheet : prepared.sheets) {
            Map<String, Object> item = sheetSummary(sheet);
            item.put("columns", sheet.columns.stream().map(AgentWorkbookService::displayHeader).toList());
            item.put("totalColumns", sheet.totalColumns.stream().map(index -> sheet.columns.get(index).header).toList());
            item.put("sampleRows", sampleRows(sheet));
            sheets.add(item);
        }
        view.put("sheets", sheets);
        List<Map<String, Object>> info = new ArrayList<>();
        for (InfoEntry entry : prepared.info) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", entry.getLabel());
            item.put("value", entry.getValue());
            info.add(item);
        }
        view.put("info", info);
        view.put("sourceAttachment", attachmentView(prepared.attachment));
        view.put("includeSourceText", prepared.includeSourceText);
        view.put("warnings", prepared.warnings);
        view.put("retention", RETENTION);
        view.put("effect", EFFECT);
        return view;
    }

    private Map<String, Object> resultView(ExcelTaskOutput output, Prepared prepared) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outputId", output.getId());
        result.put("fileName", output.getOriginalName());
        result.put("fileSize", output.getFileSize());
        result.put("status", output.getStatus());
        result.put("layout", prepared.layout);
        result.put("downloadPath", "/agent/outputs/" + output.getId() + "/download");
        result.put("sheets", prepared.sheets.stream().map(this::sheetSummary).toList());
        result.put("rowCount", prepared.totalRows);
        result.put("mismatchCount", prepared.mismatchCount);
        result.put("warnings", prepared.warnings);
        result.put("retention", RETENTION);
        result.put("note", "文件已生成并显示在对话中，用户可直接打开、转发或删除；本操作没有修改任何业务数据。");
        return result;
    }

    private Map<String, Object> summary(Prepared prepared) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("title", prepared.title);
        summary.put("layout", prepared.layout);
        summary.put("sheets", prepared.sheets.stream().map(this::sheetSummary).toList());
        summary.put("rowCount", prepared.totalRows);
        summary.put("mismatchCount", prepared.mismatchCount);
        summary.put("attachment", attachmentView(prepared.attachment));
        summary.put("includeSourceText", prepared.includeSourceText);
        summary.put("warnings", prepared.warnings);
        return summary;
    }

    private Map<String, Object> sheetSummary(PreparedSheet sheet) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", sheet.name);
        item.put("rowCount", sheet.rows.size());
        item.put("columnCount", sheet.columns.size());
        item.put("mismatchCount", sheet.mismatchCount);
        return item;
    }

    private Map<String, Object> attachmentView(AgentAttachment attachment) {
        if (attachment == null) return null;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", attachment.getId());
        view.put("name", attachment.getOriginalName());
        return view;
    }

    private List<List<String>> sampleRows(PreparedSheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        for (int row = 0; row < Math.min(PREVIEW_ROWS, sheet.rows.size()); row++) {
            List<String> values = new ArrayList<>();
            for (PreparedCell cell : sheet.rows.get(row)) values.add(abbreviate(cell.display(), 30));
            rows.add(values);
        }
        return rows;
    }

    // ---------------------------------------------------------------- helpers

    private String cleanTitle(String value) {
        String title = StringUtils.hasText(value) ? value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim() : "";
        if (title.toLowerCase(Locale.ROOT).endsWith(".xlsx")) title = title.substring(0, title.length() - 5).trim();
        title = title.replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "")
                .replaceAll("(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])", "")
                .replaceAll("(?i)\\.(png|jpe?g|webp)$", "")
                .replaceAll("[-_]20\\d{6}[-_]\\d{4,6}$", "")
                .replaceAll("[_\\s-]+$", "").replaceAll("^[_\\s-]+", "");
        if (title.matches("(单据|收据)?识别明细")) title = "单据明细";
        if (title.isEmpty()) title = "单据明细";
        return truncate(title, MAX_TITLE_CHARS);
    }

    private String cleanNote(String value) {
        return StringUtils.hasText(value) ? truncate(value.trim(), MAX_CELL_CHARS) : null;
    }

    private String digest(AgentWorkbookSpec spec) {
        try {
            return WorkbookRenderSupport.digest(objectMapper.writeValueAsBytes(spec));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Excel 参数无法序列化", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private void audit(Long operatorId, String action, String detail) {
        if (operationLogRepository == null) return;
        OperationLog entry = new OperationLog();
        entry.setOperatorId(operatorId);
        entry.setModule("AGENT_FILE");
        entry.setAction(action);
        entry.setDetail(detail.length() <= 255 ? detail : detail.substring(0, 255));
        entry.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(entry);
    }

    private static int indexOfRole(List<PreparedColumn> columns, ColumnRole role) {
        for (int index = 0; index < columns.size(); index++) if (columns.get(index).role == role) return index;
        return -1;
    }

    private static int indexOfHeader(List<PreparedColumn> columns, String header) {
        if (!StringUtils.hasText(header)) return -1;
        String wanted = header.replaceAll("\\s+", "");
        for (int index = 0; index < columns.size(); index++) {
            if (columns.get(index).header.replaceAll("\\s+", "").equals(wanted)) return index;
        }
        return -1;
    }

    static BigDecimal parseNumber(String text) {
        if (!StringUtils.hasText(text)) return null;
        String normalized = text.trim().replace("¥", "").replace("￥", "").replace("$", "")
                .replace(",", "").replace("，", "").replace(" ", "").replace(" ", "");
        if (normalized.endsWith("元")) normalized = normalized.substring(0, normalized.length() - 1);
        if (!normalized.matches("[-+]?\\d+(\\.\\d+)?")) return null;
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static LocalDate parseDate(String text) {
        String trimmed = text.trim();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(trimmed, format);
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        return null;
    }

    private static BigDecimal toDecimal(Number number) {
        if (number instanceof BigDecimal decimal) return decimal;
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
            return BigDecimal.valueOf(number.longValue());
        }
        return new BigDecimal(number.toString());
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0
                ? value.setScale(0, RoundingMode.UNNECESSARY).toPlainString() : value.stripTrailingZeros().toPlainString();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    // ---------------------------------------------------------------- internal model

    enum ColumnRole {
        NONE, QUANTITY, UNIT_PRICE, AMOUNT, CALCULATED_AMOUNT;

        static ColumnRole parse(String value) {
            if (!StringUtils.hasText(value)) return NONE;
            try {
                return ColumnRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return NONE;
            }
        }
    }

    enum ColumnType {
        TEXT, NUMBER, INTEGER, MONEY, DATE;

        boolean numeric() {
            return this == NUMBER || this == INTEGER || this == MONEY;
        }

        static ColumnType parse(String value, ColumnRole role, String header) {
            if (StringUtils.hasText(value)) {
                try {
                    return ColumnType.valueOf(value.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                    // fall through to inference
                }
            }
            if (role == ColumnRole.QUANTITY) return NUMBER;
            if (role == ColumnRole.UNIT_PRICE || role == ColumnRole.AMOUNT || role == ColumnRole.CALCULATED_AMOUNT) return MONEY;
            String compact = header == null ? "" : header.replaceAll("\\s+", "");
            if (compact.matches(".*(单价|价格|金额|合计|小计|成本|售价|总价|总额).*")) return MONEY;
            if (WorkbookRenderSupport.isNumericHeader(compact)) return NUMBER;
            return TEXT;
        }
    }

    record PreparedColumn(String header, ColumnType type, ColumnRole role) {
    }

    static final class PreparedCell {
        final String text;
        final BigDecimal number;
        final LocalDate date;
        final boolean flagged;
        final boolean truncated;
        final boolean nonNumeric;

        private PreparedCell(String text, BigDecimal number, LocalDate date, boolean flagged, boolean truncated, boolean nonNumeric) {
            this.text = text;
            this.number = number;
            this.date = date;
            this.flagged = flagged;
            this.truncated = truncated;
            this.nonNumeric = nonNumeric;
        }

        static PreparedCell empty() {
            return new PreparedCell("", null, null, false, false, false);
        }

        static PreparedCell text(String text, boolean truncated, boolean nonNumeric) {
            return new PreparedCell(text, null, null, false, truncated, nonNumeric);
        }

        static PreparedCell number(BigDecimal number, boolean flagged) {
            return new PreparedCell(null, number, null, flagged, false, false);
        }

        static PreparedCell date(LocalDate date) {
            return new PreparedCell(null, null, date, false, false, false);
        }

        String display() {
            if (number != null) return plain(number);
            if (date != null) return date.toString();
            return text == null ? "" : text;
        }
    }

    record PreparedSheet(String name, List<PreparedColumn> columns, List<List<PreparedCell>> rows, List<String> rowNotes,
                         boolean hasNotes, List<Integer> totalColumns, int mismatchCount, String note) {
    }

    record Prepared(String title, String layout, List<PreparedSheet> sheets, List<InfoEntry> info, AgentAttachment attachment,
                    Long conversationId, boolean includeSourceText, String note, List<String> warnings,
                    int totalRows, int mismatchCount) {
        String fileName(LocalDateTime now) {
            return title + "_" + now.format(FILE_STAMP) + ".xlsx";
        }
    }

    /** Cell styles are created once per workbook; POI limits the number of distinct styles. */
    private static final class Styles {
        final CellStyle header;
        final CellStyle flaggedText;
        final CellStyle totalText;
        final CellStyle text;
        final CellStyle title;
        final CellStyle meta;
        final CellStyle longText;
        private final Workbook workbook;
        private final DataFormat format;
        private final Font bodyFont;
        private final Map<String, CellStyle> cache = new HashMap<>();

        Styles(Workbook workbook) {
            this.workbook = workbook;
            this.format = workbook.createDataFormat();
            this.bodyFont = workbook.createFont(); bodyFont.setFontName("宋体"); bodyFont.setFontHeightInPoints((short) 10);
            this.text = base();
            this.longText = base(); longText.setAlignment(HorizontalAlignment.LEFT);
            this.header = base();
            Font heading = workbook.createFont(); heading.setFontName("宋体"); heading.setFontHeightInPoints((short) 10); heading.setBold(true); header.setFont(heading);
            this.title = workbook.createCellStyle();
            Font titleFont = workbook.createFont(); titleFont.setFontName("微软雅黑"); titleFont.setFontHeightInPoints((short) 16); titleFont.setBold(true);
            title.setFont(titleFont); title.setAlignment(HorizontalAlignment.CENTER); title.setVerticalAlignment(VerticalAlignment.CENTER);
            this.meta = workbook.createCellStyle(); meta.setFont(bodyFont); meta.setWrapText(true); meta.setVerticalAlignment(VerticalAlignment.CENTER);
            this.flaggedText = base(); flaggedText.setAlignment(HorizontalAlignment.LEFT);
            flaggedText.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            flaggedText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            this.totalText = base();
            Font bold = workbook.createFont();
            bold.setBold(true);
            totalText.setFont(bold);
        }

        private CellStyle base() {
            CellStyle style = workbook.createCellStyle(); style.setFont(bodyFont); style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER); style.setWrapText(true);
            style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
            return style;
        }

        CellStyle number(ColumnType type, boolean flagged) {
            return cache.computeIfAbsent("number:" + type + ":" + flagged, ignored -> {
                CellStyle style = base();
                style.setDataFormat(format.getFormat(type == ColumnType.MONEY ? "#,##0.00" : "General"));
                if (flagged) {
                    style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return style;
            });
        }

        CellStyle date(boolean flagged) {
            return cache.computeIfAbsent("date:" + flagged, ignored -> {
                CellStyle style = base();
                style.setDataFormat(format.getFormat("yyyy-mm-dd"));
                if (flagged) {
                    style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
                    style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return style;
            });
        }

        CellStyle total(ColumnType type) {
            return cache.computeIfAbsent("total:" + type, ignored -> {
                CellStyle style = base();
                style.setDataFormat(format.getFormat(type == ColumnType.MONEY ? "#,##0.00" : "General"));
                Font bold = workbook.createFont();
                bold.setBold(true);
                style.setFont(bold);
                return style;
            });
        }
    }
}
