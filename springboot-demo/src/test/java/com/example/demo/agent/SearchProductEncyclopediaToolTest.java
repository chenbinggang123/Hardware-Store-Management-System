package com.example.demo.agent;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.SearchProductEncyclopediaTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchProductEncyclopediaToolTest {
    private final SearchProductEncyclopediaTool tool = new SearchProductEncyclopediaTool();
    private final AgentToolContext context = new AgentToolContext(1L, 1L);

    @Test
    void searchesCanonicalNameAndAliasAndMarksResultsAsCandidates() {
        tool.loadEncyclopedia();

        Map<String, Object> result = cast(tool.execute(context, Map.of("keyword", "活口扳手")));
        List<?> matches = (List<?>) result.get("matches");
        Map<String, Object> first = cast(matches.get(0));

        assertEquals(AgentToolRisk.R0_READ_ONLY, tool.definition().getRisk());
        assertEquals("general_reference_candidates", result.get("resultType"));
        assertEquals("活动扳手", first.get("name"));
        assertEquals("手动工具", first.get("category"));
        assertTrue(first.get("commonUnitCandidates").toString().contains("把"));
        assertEquals("reference_only", first.get("confidence"));
    }

    @Test
    void makesNoMatchExplicitInsteadOfInventingAProduct() {
        tool.loadEncyclopedia();

        Map<String, Object> result = cast(tool.execute(context, Map.of("keyword", "不存在的五金品")));

        assertEquals(0, result.get("matchCount"));
        assertTrue(result.get("message").toString().contains("保留票面原文"));
        assertTrue(((List<?>) result.get("matches")).isEmpty());
    }

    @Test
    void clampsResultLimitAndRejectsBlankKeyword() {
        tool.loadEncyclopedia();
        Map<String, Object> result = cast(tool.execute(context, Map.of("keyword", "钉", "limit", 100)));

        assertTrue(((List<?>) result.get("matches")).size() <= 20);
        assertFalse(((List<?>) result.get("matches")).isEmpty());
        try {
            tool.execute(context, Map.of("keyword", "  "));
        } catch (IllegalArgumentException exception) {
            assertEquals("商品百科检索关键词不能为空", exception.getMessage());
            return;
        }
        throw new AssertionError("Expected blank keyword to be rejected");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
