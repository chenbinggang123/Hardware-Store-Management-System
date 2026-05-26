package com.example.demo.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户账款记录
 */
@Data
public class AccountRecord {
    private Long orderId;
    private String orderNumber;
    private Long customerId;
    private BigDecimal totalAmount;
    private BigDecimal receivedAmount;
    private BigDecimal debtAmount;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
}
