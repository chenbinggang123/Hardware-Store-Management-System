package com.example.demo.agent.tool.builtin;

import com.example.demo.agent.tool.*;
import com.example.demo.billing.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;

/** Produces an editable document draft, never a file or a posted sale. */
@Component
public class PrepareBillingDocumentTool implements AgentTool {
    private final BillingService billing;
    private final ObjectMapper mapper;
    public PrepareBillingDocumentTool(BillingService billing,ObjectMapper mapper){this.billing=billing;this.mapper=mapper;}
    public AgentToolDefinition definition(){
        Map<String,Object> line=new LinkedHashMap<>();
        for(String key:List.of("name","specification","unit","remark","sourceAmount"))line.put(key,Map.of("type",List.of("string","null")));
        line.put("quantity",Map.of("type",List.of("number","null"),"description","原始数量，未知留空，最多6位小数"));
        line.put("price",Map.of("type",List.of("number","null"),"description","原始单价，未知留空，最多2位小数"));
        Map<String,Object> props=new LinkedHashMap<>();
        for(String key:List.of("companyName","phone","customerName","date","remark","sourceNote"))props.put(key,Map.of("type",List.of("string","null")));
        props.put("lines",Map.of("type","array","minItems",1,"maxItems",200,"items",Map.of("type","object","additionalProperties",false,"properties",line,"required",List.of("name"))));
        return new AgentToolDefinition("prepare_billing_document","先准备商品单据草稿，保留来源金额和待核对信息。在开单页手动修改、核对后再选择导出图片或 Excel；不会登记销售、欠款或库存。",AgentToolRisk.R1_DRAFT,
            Map.of("type","object","additionalProperties",false,"properties",props,"required",List.of("lines")),"送到开单页修改");
    }
    private BillingContent content(Map<String,Object> args){var c=mapper.convertValue(args,BillingContent.class);if(c==null)throw new IllegalArgumentException("开单内容不能为空");c.setRequestKey(null);c.setVersion(null);
        c.setCompanyName(RecognizedTextSanitizer.clean(c.getCompanyName()));c.setPhone(RecognizedTextSanitizer.clean(c.getPhone()));c.setCustomerName(RecognizedTextSanitizer.clean(c.getCustomerName()));c.setRemark(RecognizedTextSanitizer.clean(c.getRemark()));c.setSourceNote(RecognizedTextSanitizer.clean(c.getSourceNote()));
        if(c.getLines()!=null)for(var line:c.getLines())if(line!=null){line.setName(RecognizedTextSanitizer.clean(line.getName()));line.setSpecification(RecognizedTextSanitizer.clean(line.getSpecification()));line.setUnit(RecognizedTextSanitizer.clean(line.getUnit()));line.setRemark(RecognizedTextSanitizer.clean(line.getRemark()));line.setSourceAmount(RecognizedTextSanitizer.clean(line.getSourceAmount()));line.setRemember(false);line.setAlias(null);}return billing.normalize(c,false);}
    /** 草稿只进入开单页，不登记销售、欠款、库存或商品记忆；用户在开单页点“保存并生成”才是唯一确认。 */
    @Override public boolean draftWithoutApproval(){return true;}
    @Override public boolean completesRun(){return true;}
    public Object approvalPreview(AgentToolContext ctx,Map<String,Object> args){var c=content(args);Map<String,Object> p=new LinkedHashMap<>();p.put("kind","BILLING_DRAFT");p.put("content",c);p.put("effect","只建立可编辑草稿；在开单页手动核对并确认后选择导出图片或 Excel，不登记销售、欠款、库存或商品记忆。");return p;}
    public Object execute(AgentToolContext ctx,Map<String,Object> args){var c=content(args);c.setRequestKey("agent-billing-"+ctx.getRunId());var d=billing.save(ctx.getOperatorId(),null,c);return Map.of("kind","BILLING_DOCUMENT","documentId",d.get("id"),"editUrl","billing.html#document/"+d.get("id"),"message","已准备好开单草稿，请进入开单页修改核对，再选择导出图片或 Excel。尚未生成文件或登记业务。");}
}
