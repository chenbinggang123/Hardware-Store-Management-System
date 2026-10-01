package com.example.demo.excel.dto;

import java.math.BigDecimal;

public record ProductCandidateView(Long id, String name, String barcode, String spec, String unit,
                                   BigDecimal retailPrice, BigDecimal wholesalePrice,
                                   BigDecimal costPrice, Integer stock) {
}
