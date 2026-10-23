package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.ProductRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Component
public class QueryProcurementReceiptsTool implements AgentTool {
    private final InventoryLogRepository logs;
    private final ProductRepository products;
    public QueryProcurementReceiptsTool(InventoryLogRepository logs, ProductRepository products) { this.logs=logs;this.products=products; }
    public AgentToolDefinition definition() {
        return new AgentToolDefinition("query_procurement_receipts",
                "查询指定日确认保存采购单产生的入库商品，默认今天；按实际保存入库时间而非票面日期，包含当日撤回数量及净增加量。只查当前操作人的记录。分页必须读完才能称完整清单。",AgentToolRisk.R0_READ_ONLY,
                Map.of("type","object","additionalProperties",false,"properties",Map.of("date",Map.of("type","string","description","YYYY-MM-DD，省略为上海时间今天"),"page",Map.of("type","integer","minimum",0)),"required",List.of()));
    }
    @Transactional(readOnly=true)
    public Object execute(AgentToolContext context, Map<String,Object> args) {
        if(context.getOperatorId()==null || context.getOperatorId()<=0)throw new IllegalArgumentException("请先登录");
        LocalDate date;
        try { String raw=Objects.toString(args.get("date"),"").trim();date=raw.isEmpty()?LocalDate.now(ZoneId.of("Asia/Shanghai")):LocalDate.parse(raw); }
        catch(DateTimeException e){throw new IllegalArgumentException("查询日期应为 YYYY-MM-DD");}
        int page=args.get("page") instanceof Number n?n.intValue():0;
        if(page<0)throw new IllegalArgumentException("页码不能小于0");
        var events=logs.findByOperatorIdAndChangeTypeInAndCreateTimeGreaterThanEqualAndCreateTimeLessThanOrderByCreateTimeAsc(
                context.getOperatorId(),List.of("采购单入库","采购单撤回"),date.atStartOfDay(),date.plusDays(1).atStartOfDay());
        Map<Long,long[]> amounts=new TreeMap<>();Map<Long,String> names=new HashMap<>();Map<Long,Set<Long>> sources=new HashMap<>();
        for(var event:events) {
            long[] quantity=amounts.computeIfAbsent(event.getProductId(),key->new long[2]);
            if("采购单入库".equals(event.getChangeType()))quantity[0]+=event.getQuantity();else quantity[1]-=event.getQuantity();
            names.put(event.getProductId(),event.getProductName());
            if(event.getRelatedOrderId()!=null)sources.computeIfAbsent(event.getProductId(),key->new LinkedHashSet<>()).add(event.getRelatedOrderId());
        }
        var ids=amounts.keySet().stream().skip((long)page*20).limit(20).toList();
        Map<Long,com.example.demo.entity.Product> details=new HashMap<>();products.findAllById(ids).forEach(product->details.put(product.getId(),product));
        List<Map<String,Object>> items=new ArrayList<>();
        for(Long id:ids) {
            var product=details.get(id);long[] quantity=amounts.get(id);Map<String,Object> row=new LinkedHashMap<>();
            row.put("productId",id);row.put("name",names.get(id));row.put("specification",product==null?null:product.getSpec());row.put("unit",product==null?null:product.getUnit());
            row.put("receivedQuantity",quantity[0]);row.put("withdrawnQuantity",quantity[1]);row.put("netQuantity",quantity[0]-quantity[1]);
            var documents=sources.getOrDefault(id,Set.of());row.put("documentIds",documents.stream().limit(20).toList());row.put("sourcesTruncated",documents.size()>20);items.add(row);
        }
        return Map.of("date",date.toString(),"basis","确认保存采购单产生的库存变动时间；草稿不算。规格和单位来自当前商品档案，原单详情请按documentId读取。",
                "scope","当前操作人","items",items,"page",page,"totalProducts",amounts.size(),"totalPages",(amounts.size()+19)/20,"eventCount",events.size());
    }
}
