package com.example.demo.billing;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Shared, read-only view of the local Markdown product encyclopedia. */
@Component
public class ProductEncyclopediaCatalog {
    private static final String RESOURCE_PATTERN = "classpath*:product-encyclopedia/*.md";
    private final ResourcePatternResolver resources = new PathMatchingResourcePatternResolver();
    private volatile List<Entry> entries = List.of();
    private volatile Map<String, Entry> entriesByKey = Map.of();

    @PostConstruct
    public void load() {
        try {
            List<Entry> loaded = new ArrayList<>();
            for (Resource resource : resources.getResources(RESOURCE_PATTERN)) {
                String filename = resource.getFilename();
                if (filename == null || filename.equals("README.md") || filename.startsWith("_")) continue;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Entry entry = parseRow(filename, line);
                        if (entry != null) loaded.add(entry);
                    }
                }
            }
            LinkedHashMap<String, Entry> indexed = new LinkedHashMap<>();
            loaded.forEach(entry -> indexed.putIfAbsent(entry.key(), entry));
            entries = List.copyOf(indexed.values());
            entriesByKey = Map.copyOf(indexed);
        } catch (IOException exception) {
            throw new IllegalStateException("加载商品百科失败", exception);
        }
    }

    public List<Entry> search(String keyword, int limit) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) return List.of();
        return entries.stream().filter(entry -> entry.matches(normalized))
                .sorted(Comparator.comparingInt(entry -> entry.rank(normalized)))
                .limit(Math.max(1, Math.min(50, limit))).toList();
    }

    public Optional<Entry> find(String key) { return Optional.ofNullable(entriesByKey.get(key)); }
    public int size() { return entries.size(); }

    private Entry parseRow(String filename, String rawLine) {
        String line = rawLine.trim();
        if (!line.startsWith("|")) return null;
        String body = line.substring(1);
        if (body.endsWith("|")) body = body.substring(0, body.length() - 1);
        String[] cells = body.split("\\|", -1);
        if (cells.length != 4) return null;
        String name = clean(cells[0]);
        if (name.isBlank() || name.startsWith("---") || name.equals("建议名称")) return null;
        String category = filename.replaceFirst("\\.md$", "");
        return new Entry("encyclopedia:" + category + ":" + name, category, name,
                split(clean(cells[1])), split(clean(cells[2])), clean(cells[3]));
    }

    private static List<String> split(String value) {
        if (value.isBlank()) return List.of();
        return Arrays.stream(value.split("[、，,；;]"))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }

    private static String clean(String value) { return value.trim().replace("`", "").replace("**", ""); }
    public static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、（）【】《》“”‘’·/\\\\_-]+", "");
    }

    public record Entry(String key, String category, String name, List<String> aliases,
                        List<String> units, String caution) {
        boolean matches(String keyword) {
            return contains(name, keyword) || aliases.stream().anyMatch(alias -> contains(alias, keyword));
        }
        int rank(String keyword) {
            if (normalize(name).equals(keyword)) return 0;
            if (aliases.stream().map(ProductEncyclopediaCatalog::normalize).anyMatch(keyword::equals)) return 1;
            if (normalize(name).contains(keyword)) return 2;
            return 3;
        }
        private static boolean contains(String value, String keyword) {
            String normalized = normalize(value);
            return normalized.contains(keyword) || keyword.contains(normalized);
        }
    }
}
