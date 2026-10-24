package com.example.demo.billing;

import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.builtin.PrepareBillingDocumentTool;
import com.example.demo.agent.tool.builtin.SearchProductEncyclopediaTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SpecificationKnowledgeCatalogTest {
    private final SpecificationKnowledgeCatalog knowledge = new SpecificationKnowledgeCatalog();

    @Test void retrievalIsBoundedAndCategorySpecificWithSources() {
        var rules = knowledge.search("中5、8", "钻头", 20);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).id()).isEqualTo("drill-diameter");
        assertThat(rules.get(0).sources()).allMatch(source -> source.url().startsWith("https://"));
        assertThat(knowledge.search("不存在的商品", "", 5)).isEmpty();
        assertThat(knowledge.search("", "", 5)).isEmpty();
        assertThat(knowledge.advice("螺丝刀", "PH2")).extracting(SpecificationKnowledgeCatalog.Advice::ruleId).containsExactly("screwdriver-bits");
    }

    @Test void ambiguousDiametersOnlyProduceCandidatesAndDoNotRewriteValidSymbols() {
        var advice = knowledge.advice("镀钛钻头", "中5、8").get(0);
        assertThat(advice.suggestedSpecification()).isEqualTo("Φ5.8");
        assertThat(advice.originalSpecification()).isEqualTo("中5、8");
        assertThat(advice.reason()).contains("多个尺寸", "不改原文");
        assertThat(knowledge.advice("中齿平锉", "6英寸").get(0).suggestedSpecification()).isEmpty();
        assertThat(knowledge.advice("砂纸", "P120").get(0).suggestedSpecification()).isEmpty();
        assertThat(knowledge.advice("丝锥", "M6").get(0).suggestedSpecification()).isEmpty();
        assertThat(knowledge.advice("钻头", "中齿").get(0).suggestedSpecification()).isEmpty();
        assertThat(knowledge.advice("未知商品", "P5.5")).isEmpty();
    }

    @Test void draftAutomaticallyAddsReferencesWithoutDependingOnModelToolSelection() throws Exception {
        var mapper = new ObjectMapper();
        var billing = new BillingService(null,null,null,null,mapper);
        var tool = new PrepareBillingDocumentTool(billing,mapper,knowledge);
        var args = Map.<String,Object>of("documentType","PROCUREMENT","customerName","辉达工量具","lines",List.of(
                Map.of("name","镀钛钻头","specification","Φ1","unit","支","quantity",100,"price",0.86),
                Map.of("name","P5.5","unit","支","quantity",100,"price",3.9),
                Map.of("name","中5、8","unit","支","quantity",100,"price",4.2),
                Map.of("name","砂纸","specification","P120","unit","张","quantity",1,"price",2)));
        var preview = (Map<?,?>)tool.approvalPreview(new AgentToolContext(1L,7L),args);
        var content = (BillingContent)preview.get("content");
        var line = content.getLines().get(2);
        assertThat(line.getName()).isEqualTo("镀钛钻头");
        assertThat(line.getSpecification()).isEqualTo("中5、8");
        assertThat(line.getRawName()).isEqualTo("中5、8");
        assertThat(line.getKnowledgeAdvice().get(0).suggestedSpecification()).isEqualTo("Φ5.8");
        assertThat(line.getQuantity()).isEqualByComparingTo("100");
        assertThat(line.getPrice()).isEqualByComparingTo("4.2");
        assertThat(content.getLines().get(3).getSpecification()).isEqualTo("P120");
        var restored = mapper.readValue(mapper.writeValueAsString(content),BillingContent.class);
        var normalized = billing.normalize(restored,false);
        assertThat(normalized.getLines().get(2).getKnowledgeAdvice()).isEqualTo(line.getKnowledgeAdvice());
        assertThat(normalized.getLines().get(2).getRawName()).isEqualTo("中5、8");
    }

    @Test void queryToolAcceptsOriginalSpecAndIssueAndReturnsOnlyRelevantExcerpts() {
        var catalog = new ProductEncyclopediaCatalog(); catalog.load();
        var tool = new SearchProductEncyclopediaTool(catalog,knowledge);
        var result = (Map<?,?>)tool.execute(new AgentToolContext(1L,7L),Map.of("keyword","中5、8","category","钻头", "originalSpecification","中5、8","issue","小数点和直径符号"));
        assertThat((List<?>)result.get("specificationReferences")).hasSize(1);
        assertThat(result.get("originalSpecification")).isEqualTo("中5、8");
        assertThat(result.containsKey("message")).isFalse();
    }
}
