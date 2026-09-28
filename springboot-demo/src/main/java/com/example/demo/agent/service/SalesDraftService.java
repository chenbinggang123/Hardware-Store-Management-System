package com.example.demo.agent.service;

import com.example.demo.agent.dto.CommitSalesDraftRequest;
import com.example.demo.agent.dto.SalesDraftPreview;
import com.example.demo.agent.dto.SalesDraftRequest;
import com.example.demo.entity.SalesOrder;

public interface SalesDraftService {
    SalesDraftPreview createDraft(SalesDraftRequest request, Long operatorId);
    SalesDraftPreview updateDraft(Long draftId, SalesDraftRequest request, Long operatorId);
    SalesDraftPreview previewUpdateDraft(Long draftId, SalesDraftRequest request, Long operatorId);
    SalesDraftPreview getDraft(Long draftId, Long operatorId);
    SalesOrder commitDraft(Long draftId, CommitSalesDraftRequest request, Long operatorId);
}
