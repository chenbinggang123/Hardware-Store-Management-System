package com.example.demo.finance.dto;

import java.math.BigDecimal;

public record PaymentAllocationResult(Long paymentId, BigDecimal amount, BigDecimal allocatedAmount,
                                      BigDecimal unallocatedAmount, int documentCount) {
}
