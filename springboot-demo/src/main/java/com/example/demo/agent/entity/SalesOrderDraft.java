package com.example.demo.agent.entity;

import com.example.demo.common.JsonColumnSupport;
import com.example.demo.entity.SalesOrderItem;
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
import jakarta.persistence.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "sales_order_draft")
@Data
public class SalesOrderDraft {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerId;
    private Long operatorId;
    private BigDecimal totalAmount;
    private BigDecimal receivedAmount;
    private BigDecimal debtAmount;
    private String status;
    private LocalDateTime expiresAt;
    private Long committedOrderId;

    @Column(unique = true)
    private String commitIdempotencyKey;

    @Version
    private Long version;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @Lob
    @Column(name = "items_json")
    private String itemsJson;

    @Transient
    private List<SalesOrderItem> items;

    @PostLoad
    public void loadItems() {
        items = JsonColumnSupport.readList(itemsJson, new TypeReference<List<SalesOrderItem>>() {
        });
    }

    @PrePersist
    @PreUpdate
    public void saveItems() {
        itemsJson = JsonColumnSupport.writeList(items);
    }
}
