package com.example.demo.finance.dto;

import java.math.BigDecimal;

public record AgingBucket(String code, String label, int documentCount, BigDecimal amount) {
}
