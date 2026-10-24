package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.billing.ProductEncyclopediaCatalog;
import com.example.demo.billing.SpecificationKnowledgeCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/** Searches the shared local product encyclopedia. Results are suggestions, not store records. */
@Component
public class SearchProductEncyclopediaTool implements AgentTool {
    private final ProductEncyclopediaCatalog catalog;
    private final SpecificationKnowledgeCatalog knowledge;

    public SearchProductEncyclopediaTool() { this(new ProductEncyclopediaCatalog()); }
    public SearchProductEncyclopediaTool(ProductEncyclopediaCatalog catalog) { this(catalog,new SpecificationKnowledgeCatalog()); }
    @Autowired public SearchProductEncyclopediaTool(ProductEncyclopediaCatalog catalog,SpecificationKnowledgeCatalog knowledge) { this.catalog = catalog; this.knowledge=knowledge; }

    @PostConstruct public void loadEncyclopedia() { if (catalog.size() == 0) catalog.load(); }

    @Override public AgentToolDefinition definition() {
        return new AgentToolDefinition("search_product_encyclopedia",
                "按商品名称、类别和规格疑点查阅本地百科，返回候选名称、别名、单位，以及少量相关规格条目、解释和来源链接。填写originalSpecification保留原规格、issue说明疑点。结果是核对参考，不代表本店档案、价格或库存；不得据此自动换算或纠正票据数字。",
                AgentToolRisk.R0_READ_ONLY,
                Map.of("type","object","properties",Map.of(
                        "keyword",Map.of("type","string","description","商品名称、票面写法或别名关键词"),
                        "category",Map.of("type","string","description","已识别商品类别，例如钻头、铰刀、砂纸；不确定留空"),
                        "originalSpecification",Map.of("type","string","description","规格原文，例如中5、8，不改写"),
                        "issue",Map.of("type","string","description","需要核对的疑点，例如直径符号、小数点、多个尺寸"),
                        "limit",Map.of("type","integer","description","最多返回条数，默认 8，最大 20")),
                        "required",List.of("keyword"),"additionalProperties",false));
    }

    @Override public Object execute(AgentToolContext context,Map<String,Object> arguments) {
        String keyword=arguments.get("keyword")==null?"":arguments.get("keyword").toString().trim();
        if(keyword.isBlank())throw new IllegalArgumentException("商品百科检索关键词不能为空");
        int limit=parseLimit(arguments.get("limit"));
        List<Map<String,Object>> matches=catalog.search(keyword,limit).stream().map(entry->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",entry.name());row.put("aliases",entry.aliases());
            row.put("commonUnitCandidates",entry.units());row.put("category",entry.category());row.put("caution",entry.caution());
            row.put("matchType",ProductEncyclopediaCatalog.normalize(entry.name()).equals(ProductEncyclopediaCatalog.normalize(keyword))?"exact_name":"keyword_candidate");
            row.put("confidence","reference_only");return row;
        }).toList();
        Map<String,Object> response=new LinkedHashMap<>();response.put("keyword",keyword);response.put("matchCount",matches.size());
        response.put("resultType","general_reference_candidates");response.put("notice","百科候选不是本店商品档案；单位需按本店实际商品确认，禁止据此猜测换算或修正数量、金额。");response.put("matches",matches);
        String category=Objects.toString(arguments.get("category"),"");
        String originalSpec=Objects.toString(arguments.get("originalSpecification"),"");
        response.put("specificationReferences",knowledge.search(keyword,category,Math.min(limit,3)));
        response.put("specificationAdvice",knowledge.advice(keyword+" "+category,originalSpec));
        response.put("originalSpecification",originalSpec);
        response.put("issue",Objects.toString(arguments.get("issue"),""));
        if(matches.isEmpty()&&knowledge.search(keyword,category,1).isEmpty())response.put("message","百科中没有找到匹配项；保留票面原文并待人工确认，不要虚构商品名称或单位。");return response;
    }

    private int parseLimit(Object value){if(value==null)return 8;try{return Math.max(1,Math.min(20,Integer.parseInt(value.toString())));}catch(NumberFormatException e){return 8;}}
}
