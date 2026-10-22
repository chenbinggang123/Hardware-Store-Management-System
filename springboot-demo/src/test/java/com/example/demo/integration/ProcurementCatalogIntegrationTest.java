package com.example.demo.integration;

import com.example.demo.billing.*;
import com.example.demo.catalog.*;
import com.example.demo.entity.*;
import com.example.demo.repository.*;
import com.example.demo.service.InventoryService;
import com.example.demo.dto.InventoryAdjustRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProcurementCatalogIntegrationTest {
    @Autowired BillingService billing;
    @Autowired BillingLedgerService documents;
    @Autowired ProductRepository products;
    @Autowired InventoryRepository inventories;
    @Autowired InventoryService inventoryService;
    @Autowired CatalogService catalog;
    @Autowired SupplierCatalogController suppliers;
    @Autowired com.example.demo.price.repository.ProductPriceHistoryRepository prices;

    private final String name = "采购测试商品"+UUID.randomUUID().toString().substring(0,8);
    private Map<String,Object> draft(String factory, int quantity, String price) {
        BillingContent content = new BillingContent(); content.setRequestKey(UUID.randomUUID().toString());
        content.setDocumentType("PROCUREMENT"); content.setCustomerName(factory); content.setDate(java.time.LocalDate.now().toString());
        BillingContent.Line line = new BillingContent.Line(); line.setName(name); line.setSpecification("M6"); line.setUnit("个");
        line.setQuantity(BigDecimal.valueOf(quantity)); line.setPrice(new BigDecimal(price)); content.setLines(List.of(line));
        return billing.save(1L,null,content);
    }
    private Map<String,Object> confirm(Map<String,Object> draft) {return billing.confirm(1L,(Long)draft.get("id"),(Long)draft.get("version"));}
    private Product product(String factory) {return products.lockIdentity(name,"M6","个").stream().filter(p->factory.equals(p.getSourceFactory())).findFirst().orElseThrow();}
    private int quantity(Product product) {return inventories.findByProductId(product.getId()).orElseThrow().getQuantity();}

    @Test void procurementCreatesCatalogAndUpdatesStockExactlyOnceAcrossRevisions() {
        String factory="源头厂A"+name;
        var first=draft(factory,7,"3.25");
        assertThat(products.lockIdentity(name,"M6","个")).isEmpty();
        var confirmed=confirm(first); Product product=product(factory);
        assertThat(product.getCostPrice()).isEqualByComparingTo("3.25");
        assertThat(product.getRetailPrice()).isEqualByComparingTo("0");
        assertThat(quantity(product)).isEqualTo(7);
        billing.confirm(1L,(Long)confirmed.get("id"),(Long)first.get("version"));
        assertThat(quantity(product)).isEqualTo(7);
        var second=confirm(draft(factory,3,"3.50"));
        assertThat(quantity(product)).isEqualTo(10);
        assertThat(product(factory).getCostPrice()).isEqualByComparingTo("3.50");
        documents.deleteDocument(1L,(Long)second.get("id"));
        assertThat(quantity(product)).isEqualTo(7);
        documents.reopen(1L,(Long)first.get("id"));
        assertThat(quantity(product)).isZero();
        var reopened=billing.get(1L,(Long)first.get("id"));
        BillingContent changed=(BillingContent)reopened.get("content"); changed.setVersion((Long)reopened.get("version")); changed.getLines().get(0).setQuantity(BigDecimal.valueOf(5));
        var saved=billing.save(1L,(Long)first.get("id"),changed); confirm(saved);
        assertThat(quantity(product)).isEqualTo(5);
        documents.voidDocument(1L,(Long)first.get("id"),"测试撤回");
        assertThat(quantity(product)).isZero();
        assertThat(catalog.list("all",name,factory,0,20).totalElements()).isEqualTo(1);
    }

    @Test void supplierLedgersSeparateIdenticalGoodsAndFilterActualProcurementDocuments() {
        String left="源头A"+name,right="源头B"+name;
        confirm(draft(left,4,"2.00")); confirm(draft(right,6,"5.00"));
        assertThat(product(left).getId()).isNotEqualTo(product(right).getId());
        assertThat(quantity(product(left))).isEqualTo(4); assertThat(quantity(product(right))).isEqualTo(6);
        assertThat(((Number)documents.records(1L,"","CONFIRMED","PROCUREMENT",left,0,20).get("total")).longValue()).isEqualTo(1);
        assertThat(catalog.list("all",name,"",0,20).totalElements()).isEqualTo(2);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        AppUser user=new AppUser();user.setId(1L);
        request.setAttribute(com.example.demo.config.AuthInterceptor.AUTH_SESSION_ATTRIBUTE,new com.example.demo.security.AuthSession("test",user));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try {
            List<?> groups=(List<?>)suppliers.list(left).getData();
            assertThat(groups).hasSize(1);
            Map<?,?> group=(Map<?,?>)groups.get(0);
            assertThat(group.get("recordCount")).isEqualTo(1);
            assertThat((BigDecimal)group.get("purchaseAmount")).isEqualByComparingTo("8.00");
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }

    @Test void manualCountCorrectionAndLaterProcurementUseTheCorrectCurrentQuantity() {
        String factory="手改厂"+name; confirm(draft(factory,7,"2.00")); Product product=product(factory);
        Inventory inventory=inventories.findByProductId(product.getId()).orElseThrow();
        InventoryAdjustRequest adjustment=new InventoryAdjustRequest(); adjustment.setActualQuantity(10); adjustment.setOperatorId(1L); adjustment.setReason("手动核对");
        inventoryService.adjustInventory(inventory.getId(),adjustment);
        confirm(draft(factory,3,"2.00"));
        assertThat(quantity(product)).isEqualTo(13);
    }

    @Test void missingSupplierAndFractionalInventoryAreRejectedBeforeConfirmation() {
        var value=draft("",2,"2.00");
        assertThatThrownBy(()->confirm(value)).hasMessageContaining("源头厂商");
    }

    @Test void supplierReceiptNumberSurvivesAndDuplicateReceiptCannotAddStockAgain() {
        String factory="样单厂"+name;
        var initial=draft(factory,4,"0.67");
        BillingContent content=(BillingContent)initial.get("content");
        content.setSourceDocumentNumber("0000863"); content.setSourceNote("票面为供应商送货单；明细合计与手写实付有差异，待核对");
        content.setVersion((Long)initial.get("version"));
        var saved=billing.save(1L,(Long)initial.get("id"),content);
        confirm(saved);
        var second=draft(factory,4,"0.67");
        BillingContent duplicate=(BillingContent)second.get("content");
        duplicate.setVersion((Long)second.get("version")); duplicate.setSourceDocumentNumber("0000863");
        var repeated=billing.save(1L,(Long)second.get("id"),duplicate);
        assertThatThrownBy(()->confirm(repeated)).hasMessageContaining("原单号已录入");
        assertThat(quantity(product(factory))).isEqualTo(4);
    }

    @Test void fractionalProcurementQuantityCannotBeSilentlyRounded() {
        var initial=draft("整数厂"+name,2,"0.67");
        BillingContent content=(BillingContent)initial.get("content");
        content.setVersion((Long)initial.get("version")); content.getLines().get(0).setQuantity(new BigDecimal("1.5"));
        var saved=billing.save(1L,(Long)initial.get("id"),content);
        assertThatThrownBy(()->confirm(saved)).hasMessageContaining("数量须为整数");
    }
}
