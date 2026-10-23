package com.example.demo.billing;

import java.util.regex.Pattern;

/** Only repairs adjacent, unambiguous specification-only rows in recognized drafts. */
public final class HandwrittenItemContinuation {
    private HandwrittenItemContinuation() { }
    private static final String NUMBER = "\\d+(?:\\.\\d+)?";
    private static final String SPEC = "(?:[MmΦφØø⌀]\\s*" + NUMBER + "(?:\\s*[xX×*]\\s*" + NUMBER + ")*|" + NUMBER + "(?:\\s*[xX×*]\\s*" + NUMBER + ")+)";
    private static final Pattern SPEC_ONLY = Pattern.compile("^" + SPEC + "$");
    private static final Pattern COMBINED = Pattern.compile("^(.+?)\\s*(" + SPEC + ")$");

    public static void repair(BillingContent content) {
        if (content.getLines() == null) return;
        String previousName = null;
        int anchor = 0;
        for (int i = 0; i < content.getLines().size(); i++) {
            BillingContent.Line line = content.getLines().get(i);
            if (line == null) { previousName = null; continue; }
            String rawName = value(line.getName());
            String spec = value(line.getSpecification());
            boolean onlySpec = SPEC_ONLY.matcher(rawName).matches();
            if (rawName.isEmpty() || onlySpec) {
                // An empty row or conflicting OCR fields breaks the continuation chain.
                if ((rawName.isEmpty() && spec.isEmpty()) || (onlySpec && !spec.isEmpty() && !spec.equals(rawName))) {
                    previousName = null;
                    continue;
                }
                if (onlySpec) { line.setSpecification(rawName); line.setName(""); }
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
