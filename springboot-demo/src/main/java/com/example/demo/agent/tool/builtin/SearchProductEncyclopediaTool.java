package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.billing.ProductEncyclopediaCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/** Searches the shared local product encyclopedia. Results are suggestions, not store records. */
@Component
public class SearchProductEncyclopediaTool implements AgentTool {
    private final ProductEncyclopediaCatalog catalog;

    public SearchProductEncyclopediaTool() { this(new ProductEncyclopediaCatalog()); }
    @Autowired public SearchProductEncyclopediaTool(ProductEncyclopediaCatalog catalog) { this.catalog = catalog; }

    @PostConstruct public void loadEncyclopedia() { if (catalog.size() == 0) catalog.load(); }

    @Override public AgentToolDefinition definition() {
        return new AgentToolDefinition("search_product_encyclopedia",
                "按关键词检索项目商品百科，返回五金商品标准候选名称、常见别名、候选单位和易混提示。结果仅为通用参考，不代表本店商品档案或库存；不得据此自动换算或纠正票据数字。",
                AgentToolRisk.R0_READ_ONLY,
                Map.of("type","object","properties",Map.of(
                        "keyword",Map.of("type","string","description","商品名称、票面写法或别名关键词"),
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
        if(matches.isEmpty())response.put("message","百科中没有找到匹配项；保留票面原文并待人工确认，不要虚构商品名称或单位。");return response;
    }

    private int parseLimit(Object value){if(value==null)return 8;try{return Math.max(1,Math.min(20,Integer.parseInt(value.toString())));}catch(NumberFormatException e){return 8;}}
}
