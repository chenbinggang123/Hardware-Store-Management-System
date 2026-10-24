package com.example.demo.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Select bounded, unique references from recognized item rows, excluding supplier advertising. */
public final class ReceiptKnowledgePrefetch {
    private final SpecificationKnowledgeCatalog catalog;
    public ReceiptKnowledgePrefetch(SpecificationKnowledgeCatalog catalog) { this.catalog = catalog; }

    public List<SpecificationKnowledgeCatalog.Rule> select(List<String> texts) {
        Map<String,SpecificationKnowledgeCatalog.Rule> selected = new LinkedHashMap<>();
        for (String text : texts) {
            if (text == null) continue;
            for (String line : text.split("\\R")) {
                // Printed headings often list every product the supplier sells, not purchased items.
                if (!line.matches("^第\\d+行\\t.*") && !line.stripLeading().startsWith("|")) continue;
                for (var rule : catalog.search(line,"",5)) selected.putIfAbsent(rule.id(),rule);
            }
        }
        return selected.values().stream().limit(6).toList();
    }

    public String prompt(List<String> texts) {
        var references = select(texts);
        if (references.isEmpty()) return "";
        try {
            return "\n[系统已按商品行预取并去重的百科参考]\n"
                    + new ObjectMapper().writeValueAsString(references)
                    + "\n以上类别已查阅，无需再次调用百科；只有未覆盖的商品或新疑点才补查。参考不是票面事实；保留原文和候选，不自动更改数字。\n";
        } catch (JsonProcessingException error) { throw new IllegalStateException("百科预取序列化失败",error); }
    }
}
