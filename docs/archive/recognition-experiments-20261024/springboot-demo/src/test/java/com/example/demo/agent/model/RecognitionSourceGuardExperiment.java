package com.example.demo.agent.model;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** TEST ONLY. Reject unsupported numeric assignments; never invent a replacement from arithmetic. */
final class RecognitionSourceGuardExperiment {
    private static final Pattern ROW=Pattern.compile("(?m)^第(\\d+)行\\t(.+)$");
    private static final Pattern CELL=Pattern.compile("列(\\d+)=(.*)");
    private static final Pattern NUMBER=Pattern.compile("^(\\d+(?:\\.\\d+)?)(?:\\s*(?:个|支|件|套|包|片|台|付|对|合|盒|根|条|把|卷|箱|米|千克|公斤|kg|KG|g|L))?$");
    private final Map<Integer,Map<Integer,String>> rows=new LinkedHashMap<>();
    private final Map<Integer,String> rawRows=new LinkedHashMap<>();
    private Integer quantityColumn,priceColumn,nameColumn,specColumn;
    private boolean mergedHeader;

    RecognitionSourceGuardExperiment(String ocr) {
        var match=ROW.matcher(ocr);
        while(match.find()){
            int row=Integer.parseInt(match.group(1));var cells=new LinkedHashMap<Integer,String>();
            rawRows.put(row,match.group());
            for(String part:match.group(2).split("\\t")) {
                var cell=CELL.matcher(part);if(!cell.matches())continue;
                int col=Integer.parseInt(cell.group(1));String text=cell.group(2);cells.put(col,text);
                String header=text.replaceAll("\\s","");
                if(header.matches("(?:数量|數量)(?:单价|單價)")){quantityColumn=col;priceColumn=col+1;mergedHeader=true;}
                else {
                    if(header.matches("(?:\\d+(?:\\.\\d+)?)?(?:数量|數量)"))quantityColumn=col;
                    if(header.matches("(?:\\d+(?:\\.\\d+)?)?(?:单价|單價)"))priceColumn=col;
                }
                if(header.matches("项目|項目|品名(?:及规格|及規格)?|商品名称|商品名稱|名称|名稱"))nameColumn=col;
                if(nameColumn==null&&header.contains("项")&&header.contains("目"))nameColumn=col;
                if(header.matches("规格|規格"))specColumn=col;
            }
            rows.put(row,cells);
        }
    }

    Map<String,Object> validate(Map<String,Object> original) {
        Map<String,Object> args=new LinkedHashMap<>(original);List<String> checks=new ArrayList<>();
        // Explicit empty date bypasses the existing null -> today default. No production behavior is changed.
        if(args.get("date")==null || args.get("date").toString().isBlank()){
            args.put("date","");checks.add("日期未提供，保持留空，不补成当天");
        }
        Object list=args.get("lines");
        if(!(list instanceof List<?> lines))throw new IllegalArgumentException("无商品明细，实验组不生成空白草稿");
        if(lines.stream().noneMatch(l->l instanceof Map<?,?> m && !Objects.toString(m.get("name"),"").isBlank()))
            throw new IllegalArgumentException("OCR未提供商品明细，实验组不生成空白草稿");
        Set<Integer> used=new HashSet<>();List<Map<String,Object>> guarded=new ArrayList<>();
        for(int i=0;i<lines.size();i++){
            var line=new LinkedHashMap<String,Object>((Map<String,Object>)lines.get(i));
            Integer source=findSource(line,used);if(source!=null)used.add(source);
            var cells=source==null?Map.<Integer,String>of():rows.get(source);
            checks.add("第"+(i+1)+"条对应"+(source==null?"来源未定位":rawRows.get(source)));
            verifyField(line,"quantity","数量",quantityColumn,cells,checks,i+1);
            verifyField(line,"price","单价",priceColumn,cells,checks,i+1);
            line.remove("sourceRow");guarded.add(line);
        }
        args.put("lines",guarded);
        String note=Objects.toString(args.get("sourceNote"),"");
        if(mergedHeader)checks.add("数量/单价合并表头按相邻列解释；这是列定位规则，不代表OCR读数正确");
        args.put("sourceNote",note+"\n【字段来源校验实验】\n"+String.join("\n",checks));
        return args;
    }

    private Integer findSource(Map<String,Object> line,Set<Integer> used){
        if(line.get("sourceRow")!=null)try{
            int n=new BigDecimal(line.get("sourceRow").toString()).intValueExact();
            if(rows.containsKey(n)&&!used.contains(n))return n;
        }catch(NumberFormatException|ArithmeticException ignored){}
        String raw=identity(Objects.toString(line.get("rawName"),""));
        String spec=Objects.toString(line.get("rawSpecification"),"").strip();
        if(raw.isBlank()&&spec.isBlank())return null;
        List<Integer> matches=new ArrayList<>();
        for(var entry:rows.entrySet()){
            if(used.contains(entry.getKey()))continue;
            var cells=entry.getValue();String name=identity(Objects.toString(cells.get(nameColumn),""));
            if(name.isBlank()&&nameColumn!=null&&nameColumn!=1)name=identity(Objects.toString(cells.get(1),""));
            boolean nameMatches=!raw.isBlank()&&raw.equals(name);
            boolean specMatches=!spec.isBlank()&&(specColumn!=null?spec.equals(cells.get(specColumn)):spec.equals(name));
            if((nameMatches&&(specColumn==null||spec.isBlank()||specMatches))||(raw.isBlank()&&specMatches))matches.add(entry.getKey());
        }
        return matches.size()==1?matches.get(0):null;
    }

    private static String identity(String value){return value.replaceAll("[√✓✔]","").strip();}

    private void verifyField(Map<String,Object> line,String field,String label,Integer column,Map<Integer,String> cells,List<String> checks,int index){
        Object candidate=line.get(field);if(candidate==null)return;
        String raw=column==null?"":Objects.toString(cells.get(column),"").strip();
        // Mixed header/value cells retain only their literal header suffix, not arbitrary numeric concatenation.
        if(raw.matches("\\d+(?:\\.\\d+)?\\s*(?:数量|數量|单价|單價)"))raw=raw.replaceAll("\\s*(?:数量|數量|单价|單價)$","");
        var match=NUMBER.matcher(raw);boolean supported=false;
        try{supported=match.matches()&&new BigDecimal(match.group(1)).compareTo(new BigDecimal(candidate.toString()))==0;}catch(NumberFormatException ignored){}
        if(!supported){
            line.put(field,null);
            String evidence=label+"候选"+candidate+"未被本行"+(column==null?"已定位列":"第"+column+"列")+"支持（原文："+(raw.isBlank()?"空或未定位":raw)+"），留空待核对";
            String remark=Objects.toString(line.get("remark"),"");line.put("remark",remark.isBlank()?evidence:remark+"；"+evidence);
            checks.add("第"+index+"条："+evidence);
        }else checks.add("第"+index+"条"+label+"来源值一致："+raw+"（不代表原图读数一定正确）");
    }
}
