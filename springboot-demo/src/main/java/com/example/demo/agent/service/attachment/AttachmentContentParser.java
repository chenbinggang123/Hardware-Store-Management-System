package com.example.demo.agent.service.attachment;

import com.example.demo.agent.service.attachment.ocr.DocumentOcrClient;
import com.example.demo.agent.service.attachment.ocr.DocumentOcrResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class AttachmentContentParser {
    private static final int MAX_ROWS = 2_000;
    private static final int MAX_COLUMNS = 80;
    private static final int MAX_EXTRACTED_CHARS = 60_000;

    private final DocumentOcrClient ocrClient;
    private final String ffmpegPath;
    private final int videoTimeoutSeconds;

    @Autowired
    public AttachmentContentParser(
            DocumentOcrClient ocrClient,
            @Value("${agent.attachment.ffmpeg-path:ffmpeg}") String ffmpegPath,
            @Value("${agent.attachment.video-timeout-seconds:60}") int videoTimeoutSeconds) {
        this.ocrClient = ocrClient;
        this.ffmpegPath = ffmpegPath;
        this.videoTimeoutSeconds = Math.max(videoTimeoutSeconds, 10);
    }

    public AttachmentParseResult parse(String fileName, String mimeType, byte[] content) {
        try {
            if (isSpreadsheet(mimeType)) {
                return AttachmentParseResult.parsed(parseWorkbook(content));
            }
            if ("text/csv".equals(mimeType)) {
                return AttachmentParseResult.parsed(parseCsv(content));
            }
            if ("application/pdf".equals(mimeType)) {
                return AttachmentParseResult.parsed(parsePdf(content));
            }
            if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)) {
                return AttachmentParseResult.parsed(parseDocx(content));
            }
            if ("application/msword".equals(mimeType)) {
                return AttachmentParseResult.parsed(parseDoc(content));
            }
            if (mimeType.startsWith("image/")) {
                return parseImage(fileName, mimeType, content);
            }
            if ("video/mp4".equals(mimeType)) {
                return parseVideo(fileName, content);
            }
            return AttachmentParseResult.failed("暂不支持该文件类型");
        } catch (Exception exception) {
            return AttachmentParseResult.failed("文件解析失败：" + safeMessage(exception));
        }
    }

    private String parseWorkbook(byte[] content) throws Exception {
        StringBuilder output = new StringBuilder();
        DataFormatter formatter = new DataFormatter(java.util.Locale.CHINA);
        int rowCount = 0;
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : workbook) {
                append(output, "[工作表] " + sheet.getSheetName() + "\n");
                for (Row row : sheet) {
                    if (++rowCount > MAX_ROWS) {
                        append(output, "[内容已截断：最多读取 " + MAX_ROWS + " 行]\n");
                        return output.toString();
                    }
                    List<String> cells = new ArrayList<>();
                    int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLUMNS);
                    for (int index = 0; index < last; index++) {
                        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
                    }
                    while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                        cells.remove(cells.size() - 1);
                    }
                    if (!cells.isEmpty()) {
                        append(output, "第" + (row.getRowNum() + 1) + "行\t" + String.join("\t", cells) + "\n");
                    }
                }
            }
        }
        if (output.isEmpty()) {
            throw new IllegalArgumentException("表格中没有可读取的内容");
        }
        return output.toString();
    }

    private String parseCsv(byte[] content) {
        String source = new String(content, StandardCharsets.UTF_8);
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        List<List<String>> rows = csvRows(source);
        StringBuilder output = new StringBuilder("[CSV 文件]\n");
        for (int index = 0; index < Math.min(rows.size(), MAX_ROWS); index++) {
            List<String> row = rows.get(index);
            append(output, "第" + (index + 1) + "行\t"
                    + String.join("\t", row.subList(0, Math.min(row.size(), MAX_COLUMNS))) + "\n");
        }
        if (rows.size() > MAX_ROWS) append(output, "[内容已截断：最多读取 " + MAX_ROWS + " 行]\n");
        return output.toString();
    }

    private String parsePdf(byte[] content) throws Exception {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() == 0) throw new IllegalArgumentException("PDF 没有可读取的页面");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            if (!StringUtils.hasText(text)) {
                throw new IllegalArgumentException("PDF 中没有可读取文字；扫描件请先使用 OCR");
            }
            return "[PDF 文件，共 " + document.getNumberOfPages() + " 页]\n" + limit(text.trim());
        }
    }

    private String parseDocx(byte[] content) throws Exception {
        StringBuilder output = new StringBuilder("[Word DOCX 文件]\n");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            document.getParagraphs().stream().map(item -> item.getText().trim()).filter(StringUtils::hasText)
                    .forEach(text -> append(output, text + "\n"));
            int tableIndex = 0;
            for (XWPFTable table : document.getTables()) {
                append(output, "[表格 " + (++tableIndex) + "]\n");
                int rowIndex = 0;
                for (XWPFTableRow row : table.getRows()) {
                    if (++rowIndex > MAX_ROWS) { append(output, "[表格内容已截断]\n"); break; }
                    List<String> cells = row.getTableCells().stream().limit(MAX_COLUMNS)
                            .map(cell -> cell.getText().replaceAll("\\s+", " ").trim()).toList();
                    append(output, "第" + rowIndex + "行\t" + String.join("\t", cells) + "\n");
                }
            }
        }
        if (output.length() <= "[Word DOCX 文件]\n".length()) throw new IllegalArgumentException("Word 文档中没有可读取内容");
        return limit(output.toString());
    }

    private String parseDoc(byte[] content) throws Exception {
        try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(content));
             WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText();
            if (!StringUtils.hasText(text)) throw new IllegalArgumentException("Word 文档中没有可读取内容");
            return "[Word DOC 文件]\n" + limit(text.trim());
        }
    }

    private List<List<String>> csvRows(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < source.length() && source.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (value == ',' && !quoted) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if ((value == '\n' || value == '\r') && !quoted) {
                if (value == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
                row = new ArrayList<>();
            } else cell.append(value);
        }
        row.add(cell.toString().trim());
        if (row.stream().anyMatch(StringUtils::hasText)) rows.add(row);
        return rows;
    }

    private AttachmentParseResult parseImage(String fileName, String mimeType, byte[] content) throws Exception {
        long started = System.nanoTime();
        try {
        if (!ocrClient.isConfigured()) {
            return AttachmentParseResult.waiting("图片已保存；配置腾讯云 OCR 密钥或 HTTP OCR 服务后即可识别");
        }
        DocumentOcrResult result = ocrClient.recognize(fileName, mimeType, content);
        if (!StringUtils.hasText(result.text())) {
            throw new IllegalStateException("OCR 服务未返回可识别文字");
        }
        StringBuilder output = new StringBuilder(result.text());
        if (StringUtils.hasText(result.provider())) append(output, "\n[OCR Provider] " + result.provider());
        if (StringUtils.hasText(result.requestId())) append(output, "\n[OCR RequestId] " + result.requestId());
        for (String warning : result.warnings()) append(output, "\n[审核提醒] " + warning);
        return AttachmentParseResult.parsed(limit(output.toString()), withImageSize(result.layout(), content));
        } finally {
            org.slf4j.LoggerFactory.getLogger(AttachmentContentParser.class).info("Image OCR elapsedMs={}",(System.nanoTime()-started)/1_000_000);
        }
    }

    /** 给行位置补上原图宽高，商品页、账本页不用先加载整张图就能截出那一行。 */
    static String withImageSize(String layout, byte[] content) {
        if (!StringUtils.hasText(layout)) return layout;
        try {
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(content));
            if (image == null) return layout;
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(layout);
            node.put("width", image.getWidth());
            node.put("height", image.getHeight());
            return mapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return layout;
        }
    }

    private AttachmentParseResult parseVideo(String fileName, byte[] content) throws Exception {
        if (!ocrClient.isConfigured()) {
            return AttachmentParseResult.waiting("视频已保存；配置 OCR 服务后即可抽帧识别");
        }
        Path directory = Files.createTempDirectory("agent-video-");
        try {
            Path input = directory.resolve("input.mp4");
            Path pattern = directory.resolve("frame-%02d.jpg");
            Files.write(input, content);
            Process process = new ProcessBuilder(
                    ffmpegPath,
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", input.toString(),
                    "-vf", "fps=1/5,scale=1600:-2:force_original_aspect_ratio=decrease",
                    "-frames:v", "6",
                    "-q:v", "3",
                    pattern.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(videoTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("视频抽帧超时");
            }
            String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException("视频抽帧失败" + (processOutput.isEmpty() ? "" : "：" + processOutput));
            }
            List<Path> frames;
            try (var stream = Files.list(directory)) {
                frames = stream.filter(path -> path.getFileName().toString().startsWith("frame-"))
                        .sorted().limit(6).toList();
            }
            if (frames.isEmpty()) throw new IllegalStateException("视频中没有可识别画面");
            StringBuilder output = new StringBuilder("[视频抽帧识别：" + fileName + "，共 " + frames.size() + " 帧]\n");
            int index = 0;
            for (Path frame : frames) {
                AttachmentParseResult parsed = parseImage(frame.getFileName().toString(), "image/jpeg", Files.readAllBytes(frame));
                if (!"PARSED".equals(parsed.status())) {
                    throw new IllegalStateException(parsed.error());
                }
                append(output, "\n[代表帧 " + (++index) + "]\n" + parsed.text() + "\n");
            }
            return AttachmentParseResult.parsed(limit(output.toString()));
        } finally {
            try (var stream = Files.walk(directory)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private boolean isSpreadsheet(String mimeType) {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)
                || "application/vnd.ms-excel".equals(mimeType);
    }

    private void append(StringBuilder target, String value) {
        if (target.length() < MAX_EXTRACTED_CHARS) target.append(value);
    }

    private String limit(String value) {
        return value.length() <= MAX_EXTRACTED_CHARS ? value : value.substring(0, MAX_EXTRACTED_CHARS);
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }
}
