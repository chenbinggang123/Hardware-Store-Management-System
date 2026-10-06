package com.example.demo.excel.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the common landscape procurement-list PDF: three repeated
 * name/unit/quantity groups. The PDF remains immutable; each visual group is
 * converted into a logical sheet so the normal mapping and review workflow can
 * be reused.
 */
@Component
public class PdfProcurementParser {
    private static final List<String> UNITS = List.of("公斤", "付", "个", "块", "条", "支", "套", "只", "件", "米", "箱", "包", "把");
    private static final Set<String> IGNORED = Set.of("名称", "单位", "数量", "价格", "单价", "序号");

    public ParsedWorkbook parse(byte[] content) throws IOException {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() == 0) throw new IllegalArgumentException("PDF 没有可读取的页面");
            PositionStripper stripper = new PositionStripper();
            stripper.getText(document);
            List<ParsedWorkbook.ParsedSheet> sheets = new ArrayList<>();
            int total = 0;
            List<Map.Entry<GroupKey, List<Glyph>>> orderedGroups = stripper.groups().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(GroupKey::page)
                            .thenComparingInt(GroupKey::group))).toList();
            for (Map.Entry<GroupKey, List<Glyph>> entry : orderedGroups) {
                List<ParsedWorkbook.ParsedRow> rows = rows(entry.getValue());
                if (rows.isEmpty()) continue;
                int group = entry.getKey().group();
                int page = entry.getKey().page();
                String name = (document.getNumberOfPages() > 1 ? "第" + page + "页" : "")
                        + switch (group) { case 0 -> "左侧清单"; case 1 -> "中间清单"; default -> "右侧清单"; };
                sheets.add(new ParsedWorkbook.ParsedSheet(sheets.size(), name, 1, 2,
                        List.of("商品名称", "单位", "数量"), rows));
                total += rows.size();
            }
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("没有识别到“名称 / 单位 / 数量”采购清单；扫描件请先转为可搜索 PDF");
            }
            return new ParsedWorkbook(sheets, total);
        }
    }

    private List<ParsedWorkbook.ParsedRow> rows(List<Glyph> glyphs) {
        Map<Integer, List<Glyph>> lines = new LinkedHashMap<>();
        glyphs.stream().sorted(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x))
                .forEach(glyph -> {
                    Integer key = lines.keySet().stream().filter(value -> Math.abs(value - glyph.y()) <= 3).findFirst()
                            .orElse(Math.round(glyph.y()));
                    lines.computeIfAbsent(key, ignored -> new ArrayList<>()).add(glyph);
                });
        List<ParsedWorkbook.ParsedRow> result = new ArrayList<>();
        int number = 2;
        for (List<Glyph> line : lines.values()) {
            line.sort(Comparator.comparingDouble(Glyph::x));
            String text = line.stream().map(Glyph::text).reduce("", String::concat).replaceAll("\\s+", "").trim();
            if (!StringUtils.hasText(text) || IGNORED.contains(text) || text.contains("采购明细") || text.startsWith("日期")) continue;
            String unit = unit(text);
            if (!StringUtils.hasText(unit)) continue;
            String product = text.substring(0, text.length() - unit.length()).trim();
            if (!StringUtils.hasText(product) || IGNORED.contains(product)) continue;
            result.add(new ParsedWorkbook.ParsedRow(number++, List.of(product, unit, "")));
        }
        return result;
    }

    private String unit(String text) {
        return UNITS.stream().filter(text::endsWith).findFirst().orElse("");
    }

    private record GroupKey(int page, int group) { }
    private record Glyph(float x, float y, String text) { }

    private static final class PositionStripper extends PDFTextStripper {
        private final Map<GroupKey, List<Glyph>> groups = new LinkedHashMap<>();
        private int page;

        private PositionStripper() throws IOException { setSortByPosition(true); }

        @Override protected void startPage(org.apache.pdfbox.pdmodel.PDPage value) throws IOException {
            super.startPage(value);
            page++;
        }

        @Override protected void processTextPosition(TextPosition position) {
            String value = position.getUnicode();
            if (!StringUtils.hasText(value)) return;
            float width = position.getPageWidth();
            int group = Math.min(2, Math.max(0, (int) (position.getXDirAdj() / (width / 3f))));
            groups.computeIfAbsent(new GroupKey(page, group), ignored -> new ArrayList<>())
                    .add(new Glyph(position.getXDirAdj(), position.getYDirAdj(), value));
        }

        private Map<GroupKey, List<Glyph>> groups() { return groups; }
    }
}
