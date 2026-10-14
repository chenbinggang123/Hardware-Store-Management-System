package com.example.demo.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PaymentView(Long id, LocalDate paymentDate, BigDecimal amount, String paymentMethod, String note) {
}
