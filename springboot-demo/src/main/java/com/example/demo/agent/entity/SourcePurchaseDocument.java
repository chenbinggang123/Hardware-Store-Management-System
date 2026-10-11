package com.example.demo.agent.entity;

import com.example.demo.common.JsonColumnSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "source_purchase_document")
@Data
public class SourcePurchaseDocument {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long purchaseOrderId;
    private Long attachmentId;
    private Long supplierId;
    private String supplierName;
    private String sourceOrderNo;
    private LocalDate orderDate;
    private String buyerName;
    private String handlerName;
    private BigDecimal declaredTotalAmount;
    private BigDecimal calculatedTotalAmount;
    private Boolean receiveStock;
    private String remark;
    private Long operatorId;
    private String idempotencyKey;
    private LocalDateTime createTime;

    @Lob
    @Column(name = "items_json")
    private String itemsJson;

    @Transient
    private List<SourcePurchaseItem> items;

    @PostLoad
    public void loadItems() {
        items = JsonColumnSupport.readList(itemsJson, new TypeReference<List<SourcePurchaseItem>>() {
        });
    }

    @PrePersist
    @PreUpdate
    public void saveItems() {
        itemsJson = JsonColumnSupport.writeList(items);
    }
}
