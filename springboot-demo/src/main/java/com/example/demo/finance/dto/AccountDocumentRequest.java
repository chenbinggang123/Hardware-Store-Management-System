package com.example.demo.finance.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class AccountDocumentRequest {
    private String direction;
    private String counterpartyType;
    private Long counterpartyId;
    private String documentNumber;
    private LocalDate documentDate;
    private LocalDate dueDate;
    private BigDecimal totalAmount;
    private BigDecimal settledAmount;
    private String note;
    private String fileSourceKind;
    private Long fileSourceId;
}
