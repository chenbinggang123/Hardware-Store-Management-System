package com.example.demo.finance.dto;

import java.math.BigDecimal;
import java.util.List;

public record FinanceOverview(BigDecimal receivableOutstanding, BigDecimal payableOutstanding,
                              BigDecimal monthOccurred, BigDecimal monthSettled,
                              int openDocumentCount, BigDecimal overdueAmount, int overdueDocumentCount,
                              List<AgingBucket> agingBuckets, List<AccountDocumentView> documents) {
}
