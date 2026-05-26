package com.example.demo.entity;

import com.example.demo.common.JsonColumnSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;
import lombok.Setter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购单实体类
 */
@Entity
@Table(name = "purchase_order")
@Data
@Setter
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNumber; // 采购单号
    private Long supplierId; // 供应商ID
    private Long operatorId; // 经办人ID
    private LocalDateTime orderTime; // 下单时间
    private BigDecimal totalAmount; // 总金额
    private String status; // 状态
    private String remark; // 备注
    private LocalDateTime createTime; // 创建时间

    @Lob
    @Column(name = "items_json")
    private String itemsJson;

    @Transient
    private List<PurchaseOrderItem> items; // 明细

    @PostLoad
    public void loadItems() {
        this.items = JsonColumnSupport.readList(this.itemsJson, new TypeReference<List<PurchaseOrderItem>>() {
        });
    }

    @PrePersist
    @PreUpdate
    public void saveItems() {
        this.itemsJson = JsonColumnSupport.writeList(this.items);
    }
}
