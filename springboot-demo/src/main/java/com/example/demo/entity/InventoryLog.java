package com.example.demo.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存变动日志
 */
@Entity
@Table(name = "inventory_log")
@Data
public class InventoryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long productId;
    private String productName;
    private String changeType;
    private Integer quantity;
    private Integer beforeQuantity;
    private Integer afterQuantity;
    private Long operatorId;
    private Long relatedOrderId;
    private String remark;
    private LocalDateTime createTime;
}
