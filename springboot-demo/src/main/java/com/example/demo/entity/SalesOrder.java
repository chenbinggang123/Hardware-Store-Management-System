package com.example.demo.entity;

import lombok.Data;
import lombok.Setter;
import javax.persistence.*;
import javax.persistence.*;
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
    private LocalDateTime orderTime; // 下单时间
    private BigDecimal totalAmount; // 总金额
    private String status; // 状态
    private LocalDateTime createTime; // 创建时间
}
