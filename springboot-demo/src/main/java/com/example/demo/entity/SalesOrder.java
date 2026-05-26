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
 * 销售单实体类
 */
@Entity
@Table(name = "sales_order")
@Data
@Setter
public class SalesOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNumber; // 销售单号
    private Long customerId; // 客户ID
    private Long operatorId; // 经办人ID
    private LocalDateTime orderTime; // 下单时间
    private BigDecimal totalAmount; // 总金额
    private String status; // 状态
    private String payStatus; // 支付状态
    private BigDecimal receivedAmount; // 已收金额
    private BigDecimal debtAmount; // 欠款金额
    private LocalDateTime createTime; // 创建时间

    @Lob
    @Column(name = "items_json")
    private String itemsJson;

    @Transient
    private List<SalesOrderItem> items; // 明细

    @PostLoad
    public void loadItems() {
        this.items = JsonColumnSupport.readList(this.itemsJson, new TypeReference<List<SalesOrderItem>>() {
        });
    }

    @PrePersist
    @PreUpdate
    public void saveItems() {
        this.itemsJson = JsonColumnSupport.writeList(this.items);
    }
}
