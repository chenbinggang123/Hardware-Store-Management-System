package com.example.demo.billing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Small sourced reference library. Queries return bounded excerpts, never confirmed store data. */
@Component
public class SpecificationKnowledgeCatalog {
    public record Source(String title, String url) {}
    public record Rule(String id, String category, List<String> keywords, String title,
                       String explanation, String kind, List<Source> sources) {}
    public record Advice(String ruleId, String title, String reason, String suggestedSpecification,
                         String productName, String originalSpecification, List<Source> sources) {}
    private final List<Rule> rules;
    private static final Pattern DIAMETER_OCR = Pattern.compile("^[中巾Pp]\\s*(\\d+(?:[.、，,]\\d+)?)$");

    public SpecificationKnowledgeCatalog() {
        try (var input = new ClassPathResource("product-knowledge/specification-rules.json").getInputStream()) {
            rules = List.copyOf(new ObjectMapper().readValue(input, new TypeReference<List<Rule>>() {}));
        } catch (IOException error) { throw new IllegalStateException("无法加载规格核对资料", error); }
    }

    public List<Rule> search(String keyword, String category, int limit) {
        String query = normalize(keyword);
        String group = normalize(category);
        if (query.isBlank() && group.isBlank()) return List.of();
        return rules.stream().filter(rule -> group.isBlank() || normalize(rule.category()).contains(group)
                        || rule.keywords().stream().anyMatch(key -> normalize(key).contains(group)))
                .filter(rule -> !group.isBlank() || query.isBlank() || rule.keywords().stream().anyMatch(key -> query.contains(normalize(key)))
                        || normalize(rule.category()).contains(query))
                .sorted(Comparator.<Rule>comparingInt(rule -> rule.keywords().stream().mapToInt(key ->
                        query.contains(normalize(key)) ? normalize(key).length() : 0).max().orElse(0)).reversed())
                .limit(Math.max(1, Math.min(5, limit))).toList();
    }

    public List<Advice> advice(String name, String specification) {
        String spec = safe(specification);
        List<Advice> result = new ArrayList<>();
        for (Rule rule : search(name, "", 1)) {
            String suggestion = "";
            var match = DIAMETER_OCR.matcher(spec);
            if (rule.kind().equals("diameter") && match.matches()) {
                suggestion = "Φ" + match.group(1).replace('、', '.').replace('，', '.').replace(',', '.');
            }
            result.add(new Advice(rule.id(), rule.title(), rule.explanation(), suggestion, safe(name), spec, rule.sources()));
        }
        return List.copyOf(result);
    }

    public void annotate(BillingContent content) {
        if (content.getLines() == null) return;
        for (var line : content.getLines()) {
            if (line == null) continue;
            String spec = safe(line.getRawSpecification()).isBlank() ? line.getSpecification() : line.getRawSpecification();
            line.setKnowledgeAdvice(advice(line.getName(), spec));
        }
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String normalize(String value) { return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", ""); }
}
