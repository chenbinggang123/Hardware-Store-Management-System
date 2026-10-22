package com.example.demo.catalog;

import com.example.demo.billing.*;
import com.example.demo.common.ApiResponse;
import com.example.demo.common.CurrentOperator;
import com.example.demo.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** 按源头厂商呈现进货账本；流水直接读取统一单据，不再维护第二套采购单。 */
@RestController
@RequestMapping("/catalog/suppliers")
public class SupplierCatalogController {
    private final ProductRepository products;
    private final BillingDocumentRepository documents;
    private final ObjectMapper mapper;
    public SupplierCatalogController(ProductRepository products,BillingDocumentRepository documents,ObjectMapper mapper){this.products=products;this.documents=documents;this.mapper=mapper;}
    @GetMapping
    @Transactional(readOnly=true)
    public ApiResponse<?> list(@RequestParam(defaultValue="") String keyword) {
        Map<String,Map<String,Object>> groups=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for(var product:products.findAll()) {
            String factory=product.getSourceFactory()==null?"":product.getSourceFactory().trim();
            if(factory.isBlank())continue;
            var group=groups.computeIfAbsent(factory,this::group);
            group.put("productCount",((Number)group.get("productCount")).intValue()+1);
        }
        Long owner=CurrentOperator.idOrNull();
        if(owner!=null)for(var document:documents.findByOperatorIdAndStatusIn(owner,List.of("CONFIRMED"))) {
            BillingContent content;
            try {content=mapper.readValue(document.getContentJson(),BillingContent.class);}catch(Exception e){throw new IllegalStateException("采购单内容无法读取",e);}
            if(!"PROCUREMENT".equals(content.getDocumentType()))continue;
            var group=groups.computeIfAbsent(content.getCustomerName(),this::group);
            group.put("recordCount",((Number)group.get("recordCount")).intValue()+1);
            group.put("purchaseAmount",((BigDecimal)group.get("purchaseAmount")).add(document.getTotalAmount()));
        }
        String query=keyword.trim().toLowerCase(Locale.ROOT);
        return ApiResponse.ok(groups.values().stream().filter(group->group.get("factory").toString().toLowerCase(Locale.ROOT).contains(query)).toList());
    }
    private Map<String,Object> group(String factory){Map<String,Object> group=new LinkedHashMap<>();group.put("factory",factory);group.put("productCount",0);group.put("recordCount",0);group.put("purchaseAmount",BigDecimal.ZERO);return group;}
}
