package com.example.demo.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 收款请求
 */
@Data
public class PaymentRequest {
    private BigDecimal receivedAmount;
    private String paymentMethod;
    private String remark;
}
