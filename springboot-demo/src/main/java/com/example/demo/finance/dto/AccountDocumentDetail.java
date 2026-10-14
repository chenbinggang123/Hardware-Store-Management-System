package com.example.demo.finance.dto;

import java.util.List;

public record AccountDocumentDetail(AccountDocumentView document, List<PaymentView> payments) {
}
