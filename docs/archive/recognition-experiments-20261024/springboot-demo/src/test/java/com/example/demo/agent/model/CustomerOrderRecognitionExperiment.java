package com.example.demo.agent.model;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** TEST ONLY: customer-order counterpart of the procurement compact experiment. Never registered in production. */
final class CustomerOrderRecognitionExperiment {
    private static final String MARKER = "以下是用户上传附件的解析结果。";
    private static final Pattern ROW = Pattern.compile("(?m)^第(\\d+)行\\t(.+)$");
    private static final Pattern CELL = Pattern.compile("列(\\d+)=(.*)");
    private static final Pattern SPEC = Pattern.compile("([MΦφØø中巾Pp]\\s*\\d+(?:[.、，,]\\d+)?(?:[×xX*－-]\\d+(?:\\.\\d+)?)*)$");
    private static final String RULES = loadRules();
    private final String original;
    private final Map<Integer,Map<Integer,String>> rows;
    private final Integer nameColumn;
    private final Integer specColumn;

    private CustomerOrderRecognitionExperiment(String original, Map<Integer,Map<Integer,String>> rows, Integer name, Integer spec) {
        this.original=original;this.rows=rows;this.nameColumn=name;this.specColumn=spec;
    }

    static Optional<CustomerOrderRecognitionExperiment> select(AgentRunContext context, List<AgentToolDefinition> tools) {
        String input=context.getUserInput();
        int boundary=input.indexOf(MARKER);
        if(boundary<0 || tools.stream().noneMatch(t->"prepare_billing_document".equals(t.getName())))return Optional.empty();
        String task=input.substring(0,boundary);
        String data=input.substring(boundary);
        if(data.split("--- 附件 ID：",-1).length!=2 || data.contains("[附件内容已截断]")
                || data.split("\\[表格 ",-1).length!=2 || !data.contains("[腾讯云表格识别 V3"))return Optional.empty();
        int start=data.indexOf("[腾讯云表格识别 V3");
        int end=data.indexOf("[审核提醒]",start);
        String original=data.substring(start,end<0?data.length():end).strip();
        if(original.length()>14000)return Optional.empty();
        Map<Integer,Map<Integer,String>> rows=new LinkedHashMap<>();
        Integer name=null,spec=null;
        var matcher=ROW.matcher(original);
        while(matcher.find()) {
            Map<Integer,String> cells=new LinkedHashMap<>();
            for(String part:matcher.group(2).split("\\t")) {
                var cell=CELL.matcher(part);if(!cell.matches())continue;
                int column=Integer.parseInt(cell.group(1));String value=cell.group(2);cells.put(column,value);
                String header=value.replaceAll("\\s","");
                if(header.matches("项目|項目|品名(?:及规格|及規格)?|商品名称|商品名稱|名称|名稱"))name=column;
                if(header.matches("规格|規格|规恪"))spec=column;
                if(name==null && header.contains("项") && header.contains("目"))name=column;
            }
            rows.put(Integer.parseInt(matcher.group(1)),cells);
        }
        return rows.isEmpty() || name==null ? Optional.empty() : Optional.of(new CustomerOrderRecognitionExperiment(original,rows,name,spec));
    }

    String rules(){return RULES;}

    AgentToolDefinition compact(AgentToolDefinition definition) {
        if(!"prepare_billing_document".equals(definition.getName()))return definition;
        Map<String,Object> schema=new LinkedHashMap<>(definition.getInputSchema());
        Map<String,Object> props=new LinkedHashMap<>((Map<String,Object>)schema.get("properties"));
        props.remove("remark");props.remove("sourceNote");
        props.put("reviewCodes",Map.of("type","array","uniqueItems",true,"items",Map.of("type","string","enum",List.of("DATE_UNCLEAR","PARTIES_UNCLEAR","TOTAL_UNCLEAR"))));
        Map<String,Object> lines=new LinkedHashMap<>((Map<String,Object>)props.get("lines"));
        Map<String,Object> item=new LinkedHashMap<>((Map<String,Object>)lines.get("items"));
        Map<String,Object> fields=new LinkedHashMap<>((Map<String,Object>)item.get("properties"));
        fields.remove("rawName");fields.remove("rawSpecification");
        fields.remove("remark");
        fields.put("reviewCodes",Map.of("type","array","uniqueItems",true,"items",Map.of("type","string","enum",List.of("NAME_UNCLEAR","SPEC_UNCLEAR","UNIT_UNCLEAR","QUANTITY_UNCLEAR","PRICE_UNCLEAR","GROUP_UNCLEAR"))));
        fields.put("sourceRow",Map.of("type","integer","description","OCR第N行的N，程序据此保留完整原文"));
        item.put("properties",fields);item.put("required",List.of("name","sourceRow"));lines.put("items",item);props.put("lines",lines);schema.put("properties",props);
        return new AgentToolDefinition(definition.getName(),definition.getDescription(),definition.getRisk(),schema,definition.getApprovalTitle());
    }

    AgentModelResponse restore(AgentModelResponse response) {
        if(response.hasFinalOutput())return response;
        return AgentModelResponse.tools(response.getToolCalls().stream().map(call->{
            if(!"prepare_billing_document".equals(call.getName()))return call;
            Map<String,Object> args=new LinkedHashMap<>(call.getArguments());
            restoreReviewCodes(args);
            Object value=args.get("lines");
            if(value instanceof List<?> lines) {
                List<Map<String,Object>> restored=new ArrayList<>();
                Set<Integer> usedRows=new HashSet<>();
                for(Object line:lines) {
                    Map<String,Object> row=new LinkedHashMap<>((Map<String,Object>)line);
                    restoreReviewCodes(row);
                    Object source=row.remove("sourceRow");
                    Integer index=null;
                    try {if(source!=null)index=new BigDecimal(source.toString()).intValueExact();}catch(ArithmeticException|NumberFormatException ignored){}
                    Map<Integer,String> cells=rows.get(index);
                    if(cells==null || !usedRows.add(index))throw new IllegalArgumentException("采购识别来源行号无效或重复，请重新识别");
                    String rawName=cells.getOrDefault(nameColumn,"");
                    if(rawName.isBlank() && nameColumn!=1)rawName=cells.getOrDefault(1,"");
                    String rawSpec=specColumn==null?"":cells.getOrDefault(specColumn,"");
                    if(specColumn==null){var match=SPEC.matcher(rawName);if(match.find())rawSpec=match.group(1);}
                    row.put("rawName",rawName);row.put("rawSpecification",rawSpec);
                    if(rawName.isBlank() || SPEC.matcher(rawName).matches())
                        appendReview(row,"名称沿用候选，待确认");
                    addArithmeticReview(row);restored.add(row);
                }
                args.put("lines",restored);
            }
            String note=Objects.toString(args.get("sourceNote"),"");
            args.put("sourceNote",(note.isBlank()?"":note+"\n")+"【原始OCR记录（未确认）】\n"+original);
            return new AgentToolCall(call.getId(),call.getName(),args);
        }).toList());
    }

    private static void addArithmeticReview(Map<String,Object> row) {
        try {
            if(row.get("quantity")==null || row.get("price")==null || row.get("sourceAmount")==null)return;
            String source=row.get("sourceAmount").toString().trim();
            if(!source.matches("\\d+(?:\\.\\d+)?"))return;
            var calculated=new BigDecimal(row.get("quantity").toString()).multiply(new BigDecimal(row.get("price").toString()));
            if(calculated.compareTo(new BigDecimal(source))!=0) {
                appendReview(row,"验算金额"+calculated.stripTrailingZeros().toPlainString()+"与票面"+source+"不同，待核对");
            }
        }catch(NumberFormatException ignored) { /* Keep unresolved source values unchanged. */ }
    }

    private static void restoreReviewCodes(Map<String,Object> value) {
        Object codes=value.remove("reviewCodes");
        if(!(codes instanceof List<?> list))return;
        for(Object code:new LinkedHashSet<>(list)) {
            String message=switch(Objects.toString(code,"")) {
                case "DATE_UNCLEAR" -> "日期待核对";
                case "PARTIES_UNCLEAR" -> "买卖双方名称待核对";
                case "TOTAL_UNCLEAR" -> "票面合计待核对，见原始OCR";
                case "NAME_UNCLEAR" -> "商品名称待核对";
                case "SPEC_UNCLEAR" -> "规格待核对";
                case "UNIT_UNCLEAR" -> "单位待核对";
                case "QUANTITY_UNCLEAR" -> "数量待核对";
                case "PRICE_UNCLEAR" -> "单价待核对";
                case "GROUP_UNCLEAR" -> "商品分组待核对";
                default -> throw new IllegalArgumentException("无法识别的采购核对标记");
            };
            appendReview(value,message);
        }
    }

    private static void appendReview(Map<String,Object> value,String message) {
        String remark=Objects.toString(value.get("remark"),"");
        if(!remark.contains(message))value.put("remark",remark.isBlank()?message:remark+"；"+message);
    }

    private static String loadRules() {
        try{return new ClassPathResource("agent/customer-order-experiment.txt").getContentAsString(StandardCharsets.UTF_8);}
        catch(IOException e){throw new IllegalStateException("无法加载采购识别规则",e);}
    }
}
