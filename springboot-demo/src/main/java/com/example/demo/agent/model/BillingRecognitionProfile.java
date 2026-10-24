package com.example.demo.agent.model;

import com.example.demo.agent.harness.AgentRunContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Selected paired-comparison scheme for single-photo customer orders and procurement. */
final class BillingRecognitionProfile {
    private static final String MARKER = "以下是用户上传附件的解析结果。";
    /** 开单页“拍照识别”发来的任务带这个标记，单据类型已由用户选好，不再靠关键词猜。 */
    static final Pattern FORCED_TYPE = Pattern.compile("\\[拍照开单:(PURCHASE|RETURN|PROCUREMENT)\\]");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("--- 附件 ID：(\\d+)");
    private static final String RETURN_RULE = "\n本次是客户退货给本店，documentType用RETURN，customerName填退货客户。其余规则同上。";
    private static final Pattern ROW = Pattern.compile("(?m)^第(\\d+)行\\t(.+)$");
    private static final Pattern CELL = Pattern.compile("列(\\d+)=(.*)");
    private static final Pattern SPEC = Pattern.compile("([MΦφØø中巾Pp]\\s*\\d+(?:[.、，,]\\d+)?(?:[×xX*－-]\\d+(?:\\.\\d+)?)*)$");
    private static final String PROCUREMENT_RULES = loadRules("procurement-recognition.txt");
    private static final String CUSTOMER_RULES = loadRules("customer-order-recognition.txt");
    private final boolean customer;
    private final String forcedType;
    private final Long attachmentId;
    private final String original;
    private final Map<Integer,Map<Integer,String>> rows;
    private final Integer nameColumn;
    private final Integer specColumn;

    private BillingRecognitionProfile(String original, Map<Integer,Map<Integer,String>> rows, Integer name, Integer spec, boolean customer, String forcedType, Long attachmentId) {
        this.customer=customer;this.forcedType=forcedType;this.attachmentId=attachmentId;this.original=original;this.rows=rows;this.nameColumn=name;this.specColumn=spec;
    }

    static Optional<BillingRecognitionProfile> select(AgentRunContext context, List<AgentToolDefinition> tools) {
        String input=context.getUserInput();
        int boundary=input.indexOf(MARKER);
        if(boundary<0 || tools.stream().noneMatch(t->"prepare_billing_document".equals(t.getName())))return Optional.empty();
        String task=input.substring(0,boundary);
        var forced=FORCED_TYPE.matcher(task);
        String forcedType=forced.find()?forced.group(1):null;
        boolean customer;
        if(forcedType!=null) {
            // 用户在开单页选好了类型：直接走对应规则，不再匹配关键词。
            customer=!"PROCUREMENT".equals(forcedType);
        } else {
            String intent=task.replaceAll("(?:不要|不用|不需要|不能|不)(?:登记收付款|登记收款|登记付款|确认保存|登记库存|收款|付款)", "");
            // Do not infer intent from receipt contents, history or an ambiguous follow-up.
            boolean procurement=task.matches("(?s).*(采购|进货).*");
            customer=task.matches("(?s).*(客户.*拿货|拿货单|销售单|销货单|出货单|供货单).*");
            if(procurement==customer || !task.matches("(?s).*(建立|创建|录入|开单|编辑草稿|保存单据).*" )
                    || intent.matches("(?si).*(查询|查一下|统计|昨天|只保存|不要|不用|不需要|不建|不录|不生成|excel|表格文件|退货|付款|收款|删除|调整库存|如何|怎么).*"))return Optional.empty();
        }
        String data=input.substring(boundary);
        var attachment=ATTACHMENT_ID.matcher(data);
        Long attachmentId=attachment.find()?Long.valueOf(attachment.group(1)):null;
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
                if(header.matches((customer?"项目|項目|":"")+"品名(?:及规格|及規格)?|商品名称|商品名稱|名称|名稱"))name=column;
                if(header.matches("规格|規格|规恪"))spec=column;
                if(customer && name==null && header.contains("项") && header.contains("目"))name=column;
            }
            rows.put(Integer.parseInt(matcher.group(1)),cells);
        }
        return rows.isEmpty() || name==null ? Optional.empty() : Optional.of(new BillingRecognitionProfile(original,rows,name,spec,customer,forcedType,attachmentId));
    }

    String rules(){return customer?("RETURN".equals(forcedType)?CUSTOMER_RULES+RETURN_RULE:CUSTOMER_RULES):PROCUREMENT_RULES;}

    AgentToolDefinition compact(AgentToolDefinition definition) {
        if(!"prepare_billing_document".equals(definition.getName()))return definition;
        Map<String,Object> schema=new LinkedHashMap<>(definition.getInputSchema());
        Map<String,Object> props=new LinkedHashMap<>((Map<String,Object>)schema.get("properties"));
        props.remove("remark");props.remove("sourceNote");props.remove("sourceAttachmentId");
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
            if(forcedType!=null)args.put("documentType",forcedType);
            if(attachmentId!=null)args.put("sourceAttachmentId",attachmentId);
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
                    if(cells==null) {
                        // 行号对不上：保留这一行、标成待核对，不让整张单失败。
                        appendReview(row,"对不上原图行，请对照原图核对");
                        addArithmeticReview(row);restored.add(row);continue;
                    }
                    // 同一行里写了两样商品时会被引用两次：照样保留，提醒核对。
                    if(!usedRows.add(index))appendReview(row,"和上一行来自原图同一行，请核对");
                    row.put("sourceRow",index);
                    String rawName=cells.getOrDefault(nameColumn,"");
                    if(customer && rawName.isBlank() && nameColumn!=1)rawName=cells.getOrDefault(1,"");
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
                default -> null; // 不认识的标记直接忽略，不让整张单失败
            };
            if(message!=null)appendReview(value,message);
        }
    }

    private static void appendReview(Map<String,Object> value,String message) {
        String remark=Objects.toString(value.get("remark"),"");
        if(!remark.contains(message))value.put("remark",remark.isBlank()?message:remark+"；"+message);
    }

    private static String loadRules(String file) {
        try{return new ClassPathResource("agent/"+file).getContentAsString(StandardCharsets.UTF_8);}
        catch(IOException e){throw new IllegalStateException("无法加载单据识别规则",e);}
    }
}
