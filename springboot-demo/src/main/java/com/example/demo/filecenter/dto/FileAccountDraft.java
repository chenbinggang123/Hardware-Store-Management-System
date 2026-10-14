package com.example.demo.filecenter.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record FileAccountDraft(String documentNumber, LocalDate documentDate, BigDecimal totalAmount,
                               BigDecimal settledAmount, List<String> reviewFields) {
}
