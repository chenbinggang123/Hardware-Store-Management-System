package com.example.demo.billing;

import com.example.demo.repository.ProductRepository;
import com.example.demo.agent.workbook.*;
import com.example.demo.excel.render.WorkbookRenderSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service @Transactional
public class BillingService {
    private BillingProductSyncService productSync;
    @Autowired public void setProductSync(BillingProductSyncService productSync) { this.productSync = productSync; }
    private final BillingDocumentRepository documents;
    private final BillingMemoryRepository memories;
    private final ProductRepository products;
    private final AgentWorkbookService workbooks;
    private final ObjectMapper mapper;
    private final BillingProductUsageRepository usages;
    private final ProductEncyclopediaCatalog encyclopedia;
    public BillingService(BillingDocumentRepository documents,BillingMemoryRepository memories,ProductRepository products,AgentWorkbookService workbooks,ObjectMapper mapper) {
        this(documents,memories,products,workbooks,mapper,null,null);
    }
    @Autowired public BillingService(BillingDocumentRepository documents,BillingMemoryRepository memories,ProductRepository products,AgentWorkbookService workbooks,ObjectMapper mapper,BillingProductUsageRepository usages,ProductEncyclopediaCatalog encyclopedia) {
        this.documents=documents;this.memories=memories;this.products=products;this.workbooks=workbooks;this.mapper=mapper;this.usages=usages;this.encyclopedia=encyclopedia;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> candidates(Long owner,String keyword) {
        owner(owner);String q=text(keyword,80);LinkedHashMap<String,Map<String,Object>> found=new LinkedHashMap<>();
        if(q.isBlank()&&usages!=null){for(var usage:usages.findTop5ByOperatorIdOrderByUseCountDescLastUsedTimeDesc(owner)){resolveCandidate(owner,usage.getCandidateKey()).ifPresent(row->{row.put("useCount",usage.getUseCount());found.putIfAbsent(usage.getCandidateKey(),row);});}}
        for(var m:memories.search(owner,q,PageRequest.of(0,q.isBlank()?5:15)))addCandidate(found,memoryCandidate(m));
        if(!q.isBlank())for(var p:products.findTop10ByNameContainingIgnoreCaseOrderByIdAsc(q)){if(p.getStatus()!=null&&p.getStatus()!=1)continue;addCandidate(found,productCandidate(p));}
        if(!q.isBlank()&&encyclopedia!=null)for(var entry:encyclopedia.search(q,20))addCandidate(found,encyclopediaCandidate(entry));
        Map<String,Long> counts=usageCounts(owner,found.keySet());int[] order={0};found.values().forEach(row->{row.put("useCount",counts.getOrDefault(row.get("candidateKey"),number(row.get("useCount"))));row.put("_order",order[0]++);});
        int limit=q.isBlank()?5:15;return found.values().stream().sorted(Comparator
                .<Map<String,Object>>comparingLong(row->number(row.get("useCount"))).reversed()
                .thenComparingInt(row->(Integer)row.get("_order"))).limit(limit)
                .peek(row->row.remove("_order")).toList();
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(Long owner) { owner(owner);return documents.findTop5ByOperatorIdOrderByUpdateTimeDesc(owner).stream().map(d->{Map<String,Object> v=new LinkedHashMap<>();v.put("id",d.getId());v.put("status",d.getStatus());v.put("number",d.getDocumentNumber());v.put("content",Map.of("customerName",content(d).getCustomerName()));v.put("total",d.getTotalAmount());v.put("updatedAt",d.getUpdateTime());return v;}).toList(); }
    @Transactional(readOnly=true)
    public Map<String,Object> get(Long owner,Long id) { return view(owned(owner,id)); }
    public Map<String,Object> save(Long owner,Long id,BillingContent request) {
        owner(owner); BillingDocument doc; BillingContent content=normalize(request,false);
        if(id==null) {
            String key=text(request.getRequestKey(),80);if(key.isBlank())key=UUID.randomUUID().toString();
            var previous=documents.findByOperatorIdAndRequestKey(owner,key);
            if(previous.isPresent()) {if(previous.get().getContentJson().equals(json(content)))return view(previous.get());throw new ResponseStatusException(HttpStatus.CONFLICT,"草稿已创建，请在最近单据中打开后继续编辑；当前输入请先保留");}
            doc=new BillingDocument();doc.setRequestKey(key);doc.setOperatorId(owner);doc.setStatus("DRAFT");doc.setCreateTime(LocalDateTime.now());
        }
        else {doc=locked(owner,id); version(doc,request);if(!"DRAFT".equals(doc.getStatus()))throw new IllegalArgumentException("单据已保存，请复制为新草稿后修改");}
        doc.setContentJson(json(content)); doc.setTotalAmount(total(content));doc.setUpdateTime(LocalDateTime.now());
        doc=documents.saveAndFlush(doc);return view(doc);
    }
    public Map<String,Object> confirm(Long owner,Long id,Long expectedVersion) {
        BillingDocument doc=locked(owner,id);
        if("CONFIRMED".equals(doc.getStatus()))return view(doc);
        if(!"DRAFT".equals(doc.getStatus()))throw new IllegalArgumentException("已作废单据不能再次确认，请复制为新单");
        if(!Objects.equals(doc.getVersion(),expectedVersion))throw conflict();
        BillingContent content=normalize(content(doc),true);
        if("PROCUREMENT".equals(content.getDocumentType())&&!content.getSourceDocumentNumber().isBlank()) {
            for(var other:documents.findByOperatorIdAndStatusIn(owner,List.of("CONFIRMED"))) {
                BillingContent existing=content(other);
                if(!other.getId().equals(id)&&"PROCUREMENT".equals(existing.getDocumentType())&&content.getCustomerName().equalsIgnoreCase(existing.getCustomerName())&&content.getSourceDocumentNumber().equals(existing.getSourceDocumentNumber()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"这家厂商的原单号已录入，请在进货账本中打开，避免重复增加库存");
            }
        }
        if(productSync!=null)productSync.confirm(owner,doc,content);
        // Document lock serializes duplicate confirmation; memory and document writes commit together.
        for(var line:content.getLines()) if(line.isRemember()) {
            String fp=fingerprint(line.getName(),line.getSpecification(),line.getUnit());
            var memory=memories.findByOperatorIdAndFingerprint(owner,fp).orElseGet(BillingProductMemory::new);
            memory.setOperatorId(owner);memory.setFingerprint(fp);memory.setName(line.getName());memory.setSpecification(line.getSpecification());
            memory.setUnit(line.getUnit());memory.setAlias(line.getAlias());memory.setLastPrice(line.getPrice());memory.setUpdateTime(LocalDateTime.now());memories.save(memory);
        }
        if(usages!=null)for(var line:content.getLines())trackUsage(owner,line.getCandidateKey());
        doc.setContentJson(json(content));doc.setTotalAmount(total(content));doc.setStatus("CONFIRMED");
        doc.setDocumentNumber("KD-"+LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+doc.getId());
        doc.setUpdateTime(LocalDateTime.now());return view(documents.saveAndFlush(doc));
    }
    public Map<String,Object> excel(Long owner,Long id) {
        BillingDocument doc=owned(owner,id);if(!"CONFIRMED".equals(doc.getStatus()))throw new IllegalArgumentException("请先核对并保存单据，再导出");
        BillingContent c=content(doc);AgentWorkbookSpec spec=new AgentWorkbookSpec();spec.setTitle(("PROCUREMENT".equals(c.getDocumentType())?"采购单_":("RETURN".equals(c.getDocumentType())?"退货单_":"拿货单_"))+doc.getDocumentNumber());spec.setLayout("RECEIPT");
        var sheet=new AgentWorkbookSpec.SheetSpec();sheet.setName("单据明细");
        sheet.setColumns(List.of(new AgentWorkbookSpec.ColumnSpec("商品名称","TEXT",null),new AgentWorkbookSpec.ColumnSpec("规格","TEXT",null),
                new AgentWorkbookSpec.ColumnSpec("单位","TEXT",null),new AgentWorkbookSpec.ColumnSpec("数量","NUMBER","QUANTITY"),
                new AgentWorkbookSpec.ColumnSpec("单价","MONEY","UNIT_PRICE"),new AgentWorkbookSpec.ColumnSpec("单据金额","MONEY","AMOUNT"),new AgentWorkbookSpec.ColumnSpec("备注","TEXT",null),new AgentWorkbookSpec.ColumnSpec("来源金额原文","TEXT",null)));
        List<Object> rows=new ArrayList<>();for(var line:c.getLines()) rows.add(Arrays.asList(line.getName(),line.getSpecification(),line.getUnit(),line.getQuantity(),line.getPrice(),line.getAmount(),line.getRemark(),line.getSourceAmount()));
        sheet.setRows(rows);spec.setSheets(List.of(sheet));
        spec.setInfo(List.of(new AgentWorkbookSpec.InfoEntry("单据名称","PROCUREMENT".equals(c.getDocumentType())?"采购单":("RETURN".equals(c.getDocumentType())?"退货单":"拿货单")),new AgentWorkbookSpec.InfoEntry("单据来源","程序开单"),new AgentWorkbookSpec.InfoEntry("公司名称",c.getCompanyName()),
                new AgentWorkbookSpec.InfoEntry("电话",c.getPhone()),new AgentWorkbookSpec.InfoEntry("PROCUREMENT".equals(c.getDocumentType())?"源头厂商":"客户名称",c.getCustomerName()),
                new AgentWorkbookSpec.InfoEntry("供应商原单号",c.getSourceDocumentNumber()),new AgentWorkbookSpec.InfoEntry("日期",c.getDate()),new AgentWorkbookSpec.InfoEntry("单号",doc.getDocumentNumber()),
                new AgentWorkbookSpec.InfoEntry("备注",c.getRemark()),new AgentWorkbookSpec.InfoEntry("原始内容与核对提示",c.getSourceNote()),new AgentWorkbookSpec.InfoEntry("来源说明","PROCUREMENT".equals(c.getDocumentType())?"采购单已同步商品资料和进货库存":"已确认单据，商品资料已同步")));
        spec.setNote("数量、单价、金额来自已确认单据快照；本文件不代表已收款或已出库。");return workbooks.generate(null,owner,spec);
    }
    private static void normalizeDate(BillingContent c,boolean complete) {
        if(c.getDate()==null){c.setDate(LocalDate.now().toString());return;}
        String raw=c.getDate().trim();
        if(raw.isEmpty()){
            if(complete)throw new IllegalArgumentException("请在单据编辑页填写日期");
            c.setDate("");return;
        }
        String normalized=raw.replaceAll("[年/.]","-").replace("月","-").replace("日","");
        try{
            var match=java.util.regex.Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matcher(normalized);
            c.setDate(match.matches()?LocalDate.of(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3))).toString():LocalDate.parse(raw).toString());
        }catch(java.time.DateTimeException|NumberFormatException e){
            if(complete)throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd");
            String note="原始日期："+raw+"（待核对，请在编辑页填写）";
            c.setSourceNote(text((c.getSourceNote()==null?"":c.getSourceNote()+"\n")+note,20000));
            c.setDate("");
        }
    }

    public BillingContent normalize(BillingContent request,boolean complete) {
        if(request==null)throw new IllegalArgumentException("单据内容不能为空");
        BillingContent c=mapper.convertValue(request,BillingContent.class);
        if(c.getDocumentType()==null||c.getDocumentType().isBlank())c.setDocumentType("PURCHASE");
        if(!Set.of("PURCHASE","RETURN","PROCUREMENT").contains(c.getDocumentType()))throw new IllegalArgumentException("请选择拿货单或退货单，或采购单");
        c.setVersion(null);c.setRequestKey(null);c.setCompanyName(text(c.getCompanyName(),80));c.setPhone(text(c.getPhone(),80));c.setCustomerName(text(c.getCustomerName(),80));c.setRemark(text(c.getRemark(),200));c.setSourceNote(text(c.getSourceNote(),20000));
        normalizeDate(c,complete);
        c.setSourceDocumentNumber(text(c.getSourceDocumentNumber(),80));
        if(complete&&"PROCUREMENT".equals(c.getDocumentType())&&c.getCustomerName().isBlank())throw new IllegalArgumentException("采购单请填写源头厂商");
        if(c.getLines()==null||c.getLines().isEmpty()||c.getLines().size()>200)throw new IllegalArgumentException("每张单据需要1至200行明细");
        int index=0;for(var line:c.getLines()) {
            ++index;if(line==null)throw new IllegalArgumentException("明细行不能为空");
            line.setName(text(line.getName(),100));line.setSpecification(text(line.getSpecification(),80));line.setUnit(text(line.getUnit(),16));line.setAlias(text(line.getAlias(),100));line.setRemark(text(line.getRemark(),120));line.setSourceAmount(text(line.getSourceAmount(),100));
            line.setCandidateKey(text(line.getCandidateKey(),255));line.setReferenceSource(text(line.getReferenceSource(),80));line.setReferenceCategory(text(line.getReferenceCategory(),80));line.setReferenceCaution(text(line.getReferenceCaution(),240));
            if(numericUnit(line.getUnit())) {
                if(complete)throw new IllegalArgumentException("第"+index+"行单位不能是数字，请核对原单据");
                String evidence="单位原文："+line.getUnit()+"，疑似识别错误，待确认";
                line.setReferenceCaution(text(evidence+"；"+line.getReferenceCaution(),240));
                line.setRemark(text(evidence+"；"+line.getRemark(),120));
                line.setUnit("");line.setRemember(false);
            }
            line.setReferenceAliases(textList(line.getReferenceAliases(),12,80));line.setUnitCandidates(textList(line.getUnitCandidates(),12,16).stream().filter(unit -> !numericUnit(unit)).toList());
            number(line.getQuantity(),6,"数量");number(line.getPrice(),2,"单价");
            if(line.getQuantity()!=null&&line.getQuantity().signum()<=0)throw new IllegalArgumentException("第"+index+"行数量应大于0");
            if(complete&&"PROCUREMENT".equals(c.getDocumentType())&&line.getQuantity()!=null&&line.getQuantity().stripTrailingZeros().scale()>0)throw new IllegalArgumentException("第"+index+"行采购库存数量须为整数，请调整计量单位后保存");
            if(complete && (line.getName().isBlank()||line.getUnit().isBlank()||line.getQuantity()==null||line.getPrice()==null))throw new IllegalArgumentException("第"+index+"行请补齐商品名称、单位、数量和单价");
            line.setAmount(line.getQuantity()==null||line.getPrice()==null?null:line.getQuantity().multiply(line.getPrice()).setScale(2,RoundingMode.HALF_UP));
            if(line.getAmount()!=null&&line.getAmount().compareTo(new BigDecimal("9999999999.99"))>0)throw new IllegalArgumentException("单行金额过大");
        }
        if(total(c).compareTo(new BigDecimal("9999999999.99"))>0)throw new IllegalArgumentException("单据总额过大");
        return c;
    }
    private static boolean numericUnit(String value) {
        if(value==null)return false;
        String normalized=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC).replaceAll("\\s", "");
        return normalized.matches("[+\\-0-9.,/]+") && normalized.matches(".*[0-9].*");
    }
    private static void number(BigDecimal value,int scale,String label) {if(value!=null&&(value.signum()<0||value.stripTrailingZeros().scale()>scale||value.abs().compareTo(new BigDecimal("999999999"))>0))throw new IllegalArgumentException(label+"超出范围或精度，不能自动取整");}
    private BigDecimal total(BillingContent c) {return c.getLines().stream().map(BillingContent.Line::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);}
    private Map<String,Object> view(BillingDocument d) {Map<String,Object> v=new LinkedHashMap<>();v.put("id",d.getId());v.put("version",d.getVersion());v.put("status",d.getStatus());v.put("number",d.getDocumentNumber());v.put("content",content(d));v.put("total",d.getTotalAmount());v.put("updatedAt",d.getUpdateTime());return v;}
    private BillingContent content(BillingDocument d) {try{return mapper.readValue(d.getContentJson(),BillingContent.class);}catch(Exception e){throw new IllegalStateException("单据内容无法读取",e);}}
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("单据内容格式无效",e);}}
    private BillingDocument owned(Long owner,Long id) {owner(owner);return documents.findById(id).filter(d->owner.equals(d.getOperatorId())).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"单据不存在"));}
    private BillingDocument locked(Long owner,Long id) {owner(owner);return documents.lockOwned(id,owner).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"单据不存在"));}
    private void addCandidate(Map<String,Map<String,Object>> found,Map<String,Object> row){found.putIfAbsent(row.get("candidateKey").toString(),row);}
    private Map<String,Object> memoryCandidate(BillingProductMemory m){
        String key="memory:"+fingerprint(m.getName(),m.getSpecification(),m.getUnit());Map<String,Object> row=new LinkedHashMap<>();
        row.put("candidateKey",key);row.put("name",m.getName());row.put("specification",m.getSpecification());row.put("unit",m.getUnit());row.put("alias",m.getAlias());
        row.put("aliases",m.getAlias()==null||m.getAlias().isBlank()?List.of():List.of(m.getAlias()));row.put("unitCandidates",m.getUnit()==null||m.getUnit().isBlank()?List.of():List.of(m.getUnit()));
        row.put("price",m.getLastPrice());row.put("source","已确认记忆 · 上次开单价");row.put("sourceType","MEMORY");row.put("category","");row.put("caution","");return row;
    }
    private Map<String,Object> productCandidate(com.example.demo.entity.Product p){
        Map<String,Object> row=new LinkedHashMap<>();row.put("candidateKey","product:"+p.getId());row.put("name",p.getName());row.put("specification",p.getSpec());row.put("unit",p.getUnit());row.put("alias","");
        row.put("aliases",List.of());row.put("unitCandidates",p.getUnit()==null||p.getUnit().isBlank()?List.of():List.of(p.getUnit()));row.put("price",p.getRetailPrice());row.put("costPrice",p.getCostPrice());row.put("factory",p.getSourceFactory());
        row.put("source","商品档案 · 零售价参考");row.put("sourceType","PRODUCT");row.put("category","");row.put("caution","");return row;
    }
    private Map<String,Object> encyclopediaCandidate(ProductEncyclopediaCatalog.Entry entry){
        Map<String,Object> row=new LinkedHashMap<>();row.put("candidateKey",entry.key());row.put("name",entry.name());row.put("specification","");row.put("unit","");row.put("alias","");
        row.put("aliases",entry.aliases());row.put("unitCandidates",entry.units());row.put("price",null);row.put("source","商品百科 · 通用参考");row.put("sourceType","ENCYCLOPEDIA");
        row.put("category",entry.category());row.put("caution",entry.caution());return row;
    }
    private Optional<Map<String,Object>> resolveCandidate(Long owner,String key){
        if(key==null||key.isBlank())return Optional.empty();
        if(key.startsWith("encyclopedia:")&&encyclopedia!=null)return encyclopedia.find(key).map(this::encyclopediaCandidate);
        if(key.startsWith("memory:"))return memories.findByOperatorIdAndFingerprint(owner,key.substring("memory:".length())).map(this::memoryCandidate);
        if(key.startsWith("product:"))try{return products.findById(Long.parseLong(key.substring("product:".length()))).map(this::productCandidate);}catch(NumberFormatException ignored){return Optional.empty();}
        return Optional.empty();
    }
    private Map<String,Long> usageCounts(Long owner,Collection<String> keys){
        if(usages==null||keys.isEmpty())return Map.of();Map<String,Long> result=new HashMap<>();
        for(var usage:usages.findByOperatorIdAndCandidateKeyIn(owner,keys))result.put(usage.getCandidateKey(),usage.getUseCount());return result;
    }
    private void trackUsage(Long owner,String key){
        if(usages==null||key==null||key.isBlank()||resolveCandidate(owner,key).isEmpty())return;
        BillingProductUsage usage=usages.findByOperatorIdAndCandidateKey(owner,key).orElseGet(BillingProductUsage::new);usage.setOperatorId(owner);usage.setCandidateKey(key);
        usage.setUseCount((usage.getUseCount()==null?0:usage.getUseCount())+1);usage.setLastUsedTime(LocalDateTime.now());usages.save(usage);
    }
    private static long number(Object value){return value instanceof Number n?n.longValue():0L;}
    private static List<String> textList(List<String> values,int maxItems,int itemLimit){if(values==null)return List.of();return values.stream().filter(Objects::nonNull).map(value->text(value,itemLimit)).filter(value->!value.isBlank()).distinct().limit(maxItems).toList();}
    private static void owner(Long id){if(id==null||id<=0)throw new IllegalArgumentException("请先登录");}
    private static void version(BillingDocument d,BillingContent r){if(r==null||!Objects.equals(d.getVersion(),r.getVersion()))throw conflict();}
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"草稿已在其它页面修改，请重新打开后编辑");}
    private static String text(String value,int limit){String v=value==null?"":value.trim();if(v.length()>limit)throw new IllegalArgumentException("内容过长，最多"+limit+"字");return v;}
    private static String fingerprint(String name,String spec,String unit){return WorkbookRenderSupport.digest((normalizeKey(name)+"\u0000"+normalizeKey(spec)+"\u0000"+normalizeKey(unit)).getBytes(StandardCharsets.UTF_8));}
    private static String normalizeKey(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
}
