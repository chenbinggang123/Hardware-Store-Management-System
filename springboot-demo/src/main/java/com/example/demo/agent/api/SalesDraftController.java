package com.example.demo.agent.api;

import com.example.demo.agent.dto.CommitSalesDraftRequest;
import com.example.demo.agent.dto.SalesDraftPreview;
import com.example.demo.agent.dto.SalesDraftRequest;
import com.example.demo.agent.service.SalesDraftService;
import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.entity.SalesOrder;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent/sales-drafts")
public class SalesDraftController {
    private final SalesDraftService salesDraftService;

    public SalesDraftController(SalesDraftService salesDraftService) {
        this.salesDraftService = salesDraftService;
    }

    @PostMapping
    public ApiResponse<SalesDraftPreview> create(
            @RequestBody SalesDraftRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("销售草稿创建成功",
                salesDraftService.createDraft(request, currentUserId(httpRequest)));
    }

    @PutMapping("/{draftId}")
    public ApiResponse<SalesDraftPreview> update(
            @PathVariable Long draftId,
            @RequestBody SalesDraftRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("销售草稿更新成功",
                salesDraftService.updateDraft(draftId, request, currentUserId(httpRequest)));
    }

    @GetMapping("/{draftId}")
    public ApiResponse<SalesDraftPreview> get(
            @PathVariable Long draftId,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("销售草稿查询成功",
                salesDraftService.getDraft(draftId, currentUserId(httpRequest)));
    }

    @PostMapping("/{draftId}/commit")
    public ApiResponse<SalesOrder> commit(
            @PathVariable Long draftId,
            @RequestBody CommitSalesDraftRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("销售单创建成功",
                salesDraftService.commitDraft(draftId, request, currentUserId(httpRequest)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
