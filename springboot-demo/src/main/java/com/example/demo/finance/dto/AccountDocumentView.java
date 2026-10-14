package com.example.demo.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record AccountDocumentView(Long id, String direction, String counterpartyType, Long counterpartyId,
                                  String counterpartyName, String documentNumber, LocalDate documentDate, LocalDate dueDate,
                                  BigDecimal totalAmount, BigDecimal settledAmount, BigDecimal outstandingAmount,
                                  String status, String note, String fileSourceKind, Long fileSourceId,
                                  String businessSourceType, Long businessSourceId,
                                  LocalDateTime updateTime) {
}
