package com.example.demo.billing;

import com.example.demo.agent.service.AgentOutputService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Explicitly maps a single bill table, refusing structures the editor cannot represent. */
@Service
public class BillingImportService {
    private final AgentOutputService outputs;
    private final BillingService billing;
    private final ObjectMapper mapper;
    private static final Map<String,String> FIELDS=Map.ofEntries(
        Map.entry("商品名称","name"),Map.entry("商品","name"),Map.entry("项目","name"),Map.entry("品名","name"),
        Map.entry("规格","specification"),Map.entry("型号","specification"),Map.entry("规格型号","specification"),
        Map.entry("单位","unit"),Map.entry("数量","quantity"),Map.entry("单价","price"),Map.entry("单价（元）","price"),
        Map.entry("金额","sourceAmount"),Map.entry("金额（元）","sourceAmount"),Map.entry("票面金额","sourceAmount"),Map.entry("单据金额","sourceAmount"),
        Map.entry("来源金额原文","originalAmount"),Map.entry("备注","remark"),Map.entry("原图复核说明","reviewNote"),Map.entry("识别说明","reviewNote"),Map.entry("核算金额","calculated"),Map.entry("序号","sequence"),Map.entry("行号","sequence"));
    public BillingImportService(AgentOutputService outputs,BillingService billing,ObjectMapper mapper){this.outputs=outputs;this.billing=billing;this.mapper=mapper;}
    public BillingContent preview(Long owner,Long outputId) {
        return convert(outputs.readWorkbookContent(outputId,owner,null,201));
    }
    @SuppressWarnings("unchecked")
    BillingContent convert(Map<String,Object> workbook) {
        List<Map<String,Object>> sheets=(List<Map<String,Object>>)workbook.get("sheets");
        if(sheets==null||sheets.size()!=1)throw new IllegalArgumentException("开单页目前支持单张商品明细表；多工作表请下载 Excel 编辑，原文件已保留");
        var sheet=sheets.get(0);List<String> columns=(List<String>)sheet.get("columns");List<List<String>> rows=(List<List<String>>)sheet.get("rows");
        if(Boolean.TRUE.equals(sheet.get("truncated"))||rows==null||rows.isEmpty()||rows.size()>200)throw new IllegalArgumentException("开单页支持1至200行完整明细，不能截取部分内容生成单据");
        List<String> keys=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String column:columns){String key=FIELDS.get(column.trim());if(key==null)throw new IllegalArgumentException("此表包含开单页暂不支持的列「"+column+"」，请下载编辑，以免丢失内容");if(!seen.add(key))throw new IllegalArgumentException("存在重复含义的列，请先整理表头："+column);keys.add(key);}
        if(!seen.containsAll(List.of("name","quantity","price")))throw new IllegalArgumentException("此文件不是商品开单明细，需包含商品名称、数量、单价；原文件仍可下载编辑");
        BillingContent c=new BillingContent();List<BillingContent.Line> lines=new ArrayList<>();
        for(int r=0;r<rows.size();r++) {
            var line=new BillingContent.Line();var values=rows.get(r);
            for(int col=0;col<keys.size();col++){String value=col<values.size()?RecognizedTextSanitizer.clean(values.get(col)):"";String key=keys.get(col);switch(key){
                case "name"->line.setName(value);case "specification"->line.setSpecification(value);case "unit"->line.setUnit(value);case "remark","reviewNote"->{if(value!=null&&!value.isBlank())line.setRemark(line.getRemark()==null?value:line.getRemark()+"；"+value);}
                case "quantity"->line.setQuantity(number(value,r,"数量"));case "price"->line.setPrice(number(value,r,"单价"));
                case "sourceAmount"->{if(line.getSourceAmount()==null)line.setSourceAmount(value);}
                case "originalAmount"->{if(value!=null&&!value.isBlank())line.setSourceAmount(value);}
                default -> {} // sequence and calculated amount are rebuilt; original rows are retained below.
            }}lines.add(line);
        }c.setLines(lines);
        List<Map<String,String>> info=(List<Map<String,String>>)workbook.getOrDefault("info",List.of());
        for(var item:info){String label=item.get("label"),value=RecognizedTextSanitizer.clean(item.get("value"));if(value==null)continue;switch(label){
            case "公司名称","本店名称","开票方"->c.setCompanyName(value);case "客户名称","购货单位"->c.setCustomerName(value);
            case "电话","联系电话"->c.setPhone(value);case "日期","单据日期"->{if(value.matches("\\d{4}-\\d{2}-\\d{2}"))c.setDate(value);}
            case "备注"->c.setRemark(value);default -> {}
        }}
        try{c.setSourceNote("来源文件 #"+workbook.get("outputId")+"："+workbook.get("fileName")+"\n以下为导入时原始内容。当前单据金额按修改后的数量×单价计算，请对照来源金额核对；原文件不会覆盖。\n"+mapper.writeValueAsString(Map.of("工作表",sheets,"单据信息",info)));}
        catch(Exception e){throw new IllegalArgumentException("原始内容无法保留，请下载原文件编辑",e);}
        return billing.normalize(c,false);
    }
    private BigDecimal number(String s,int row,String label){if(s==null||s.isBlank())return null;try{if(!s.trim().matches("[+-]?(?:\\d+|\\d{1,3}(?:,\\d{3})+)(?:\\.\\d+)?"))throw new NumberFormatException();return new BigDecimal(s.trim().replace(",",""));}catch(Exception e){throw new IllegalArgumentException("第"+(row+1)+"行"+label+"含有非数字内容「"+s+"」，请先在原表中核对");}}
}
