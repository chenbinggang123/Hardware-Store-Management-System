package com.example.demo.agent.dto;

import lombok.Data;

@Data
public class CommitSalesDraftRequest {
    private Long draftVersion;
    private String idempotencyKey;
}
