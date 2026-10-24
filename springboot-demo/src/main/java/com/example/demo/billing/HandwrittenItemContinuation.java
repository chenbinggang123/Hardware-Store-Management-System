package com.example.demo.billing;

import java.util.regex.Pattern;

/** Repairs consecutive specification rows while preserving their own values and OCR evidence. */
public final class HandwrittenItemContinuation {
    public static final String MODEL_RULE = "\n续行不限两行：同一商品可连续三行或更多行省略名称，始终追溯最近明确商品名。名称无法识别但本行带数字规格时，可沿用该名称并改用本行规格，例如镀钛钻头Φ1之后的P5.5、中5、8、未识别8.5继续归为镀钛钻头。P、中、巾可能是直径符号误识别，规格原文保留待核对，不擅改数值或小数点；名称含明确商品词时不能只因带数字就覆盖名称。新明确商品名切换名称来源，空行、标题、跨表中断沿用；数量、价格、金额、单位只取本行，补全行注明名称来源和待确认，不启用长期记忆。\n";
    private HandwrittenItemContinuation() { }
    private static final String NUMBER = "\\d+(?:[.、，,]\\d+)?";
    // P/中/巾 are common OCR readings of handwritten diameter marks. Keep the source spelling.
    private static final String SPEC = "(?:[MmΦφØø⌀Pp中巾]\\s*" + NUMBER + "(?:\\s*[xX×*－-]\\s*" + NUMBER + ")*|" + NUMBER + "(?:\\s*[xX×*－-]\\s*" + NUMBER + ")+)";
    private static final Pattern SPEC_ONLY = Pattern.compile("^(?:" + SPEC + "|" + NUMBER + ")$");
    private static final Pattern COMBINED = Pattern.compile("^(.+?)\\s*(" + SPEC + ")$");
    private static final Pattern UNKNOWN = Pattern.compile("^(?:[?？�\\s]|未识别|无法识别|不明|名称不清|品名不清|名称待核对|品名待核对|未知|商品名不详|[（(](?:未识别|无法识别|不明)[）)])+[：:\\s]*");

    public static void repair(BillingContent content) {
        if (content.getLines() == null) return;
        String previousName = null;
        int anchor = 0;
        for (int i = 0; i < content.getLines().size(); i++) {
            BillingContent.Line line = content.getLines().get(i);
            if (line == null) { previousName = null; continue; }
            String rawName = value(line.getName());
            String spec = value(line.getSpecification());
            String candidate = UNKNOWN.matcher(rawName).replaceFirst("").trim();
            boolean unknownName = !candidate.equals(rawName);
            boolean onlySpec = SPEC_ONLY.matcher(candidate).matches();
            if (rawName.isEmpty() || onlySpec || (unknownName && candidate.isEmpty())) {
                // An empty row or conflicting OCR fields breaks the continuation chain.
                if ((!onlySpec && spec.isEmpty()) || (onlySpec && !spec.isEmpty() && !spec.equals(candidate))) {
                    previousName = null;
                    continue;
                }
                if (onlySpec) { line.setSpecification(candidate); line.setName(""); }
                if (previousName != null) {
                    line.setName(previousName);
                    String evidence = "名称沿用第" + anchor + "行，待确认；本行原文：" + (rawName.isEmpty() ? spec : rawName);
                    line.setReferenceCaution(append(line.getReferenceCaution(), evidence));
                    line.setRemark(append(line.getRemark(), evidence));
                    line.setRemember(false);
                }
                continue;
            }
            var combined = COMBINED.matcher(rawName);
            if (spec.isEmpty() && combined.matches() && combined.group(1).matches(".*[\\p{L}].*")) {
                line.setName(combined.group(1).trim());
                line.setSpecification(combined.group(2).trim());
                line.setRemark(append(line.getRemark(), "本行原文：" + rawName));
            }
            previousName = value(line.getName());
            anchor = i + 1;
        }
    }

    private static String value(String text) { return text == null ? "" : text.trim(); }
    private static String append(String original, String text) { return value(original).isEmpty() ? text : original + "；" + text; }
}
