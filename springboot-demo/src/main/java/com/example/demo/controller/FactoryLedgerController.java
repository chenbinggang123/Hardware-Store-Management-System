package com.example.demo.controller;

import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.dto.FactoryLedgerCreateRequest;
import com.example.demo.dto.FactoryLedgerSettlementStatusRequest;
import com.example.demo.dto.FactoryLedgerTransactionRequest;
import com.example.demo.service.FactoryLedgerService;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/factory-ledgers")
public class FactoryLedgerController {
    private final FactoryLedgerService factoryLedgerService;

    public FactoryLedgerController(FactoryLedgerService factoryLedgerService) {
        this.factoryLedgerService = factoryLedgerService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok("工厂账本查询成功", factoryLedgerService.list(month, keyword));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(
            @RequestBody FactoryLedgerCreateRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("工厂账本新建成功", factoryLedgerService.create(request, currentUserId(httpRequest)));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable Long id,
            @RequestParam(required = false) String month) {
        return ApiResponse.ok("工厂账本详情查询成功", factoryLedgerService.get(id, month));
    }

    @PostMapping("/{id}/transactions")
    public ApiResponse<Map<String, Object>> addTransaction(
            @PathVariable Long id,
            @RequestBody FactoryLedgerTransactionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("账本记录保存成功",
                factoryLedgerService.addTransaction(id, request, currentUserId(httpRequest)));
    }

    @PutMapping("/{id}/settlements/{month}/status")
    public ApiResponse<Map<String, Object>> updateSettlementStatus(
            @PathVariable Long id,
            @PathVariable String month,
            @RequestBody FactoryLedgerSettlementStatusRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.ok("结账进度更新成功",
                factoryLedgerService.setSettlementStatus(id, month, request.getStatus(), currentUserId(httpRequest)));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("无法识别当前操作人");
        }
        return session.getUser().getId();
    }
}
