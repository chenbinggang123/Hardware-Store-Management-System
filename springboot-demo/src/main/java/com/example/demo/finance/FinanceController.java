package com.example.demo.finance;

import com.example.demo.common.ApiResponse;
import com.example.demo.agent.dto.AgentFileView;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.finance.dto.AccountDocumentDetail;
import com.example.demo.finance.dto.AccountDocumentRequest;
import com.example.demo.finance.dto.AccountSyncPreview;
import com.example.demo.finance.dto.CounterpartyPaymentRequest;
import com.example.demo.finance.dto.FinanceOverview;
import com.example.demo.finance.dto.PaymentAllocationResult;
import com.example.demo.finance.dto.PaymentRecordRequest;
import com.example.demo.finance.dto.StatementExportRequest;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

@RestController
@RequestMapping("/finance")
public class FinanceController {
    private final FinanceService service;
    private final OrderAccountSyncService syncService;
    private final FinanceStatementService statementService;
    public FinanceController(FinanceService service, OrderAccountSyncService syncService,
                             FinanceStatementService statementService) {
        this.service = service;
        this.syncService = syncService;
        this.statementService = statementService;
    }

    @GetMapping
    public ApiResponse<FinanceOverview> overview(@RequestParam(required = false) String direction,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) YearMonth month,
                                                 HttpServletRequest request) {
        return ApiResponse.ok(service.overview(userId(request), direction, status, keyword, month));
    }
    @PostMapping("/documents")
    public ApiResponse<AccountDocumentDetail> create(@RequestBody AccountDocumentRequest body, HttpServletRequest request) {
        return ApiResponse.ok("往来账已建立", service.create(userId(request), body));
    }
    @GetMapping("/documents/{id}")
    public ApiResponse<AccountDocumentDetail> detail(@PathVariable Long id, HttpServletRequest request) {
        return ApiResponse.ok(service.detail(userId(request), id));
    }
    @PostMapping("/documents/{id}/payments")
    public ApiResponse<AccountDocumentDetail> payment(@PathVariable Long id, @RequestBody PaymentRecordRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok("收付款已登记", service.recordPayment(userId(request), id, body));
    }
    @PostMapping("/payments")
    public ApiResponse<PaymentAllocationResult> counterpartyPayment(@RequestBody CounterpartyPaymentRequest body,
                                                                    HttpServletRequest request) {
        return ApiResponse.ok("收付款已自动分配", service.recordCounterpartyPayment(userId(request), body));
    }
    @GetMapping("/sync-preview")
    public ApiResponse<AccountSyncPreview> syncPreview(HttpServletRequest request) {
        return ApiResponse.ok(syncService.preview(userId(request)));
    }
    @PostMapping("/sync-orders")
    public ApiResponse<AccountSyncPreview> syncOrders(HttpServletRequest request) {
        return ApiResponse.ok("订单账款已同步", syncService.syncHistory(userId(request)));
    }
    @PostMapping("/statements")
    public ApiResponse<AgentFileView> exportStatement(@RequestBody StatementExportRequest body,
                                                       HttpServletRequest request) {
        return ApiResponse.ok("对账单已生成", statementService.export(userId(request), body));
    }
    private Long userId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) throw new IllegalArgumentException("无法识别当前操作人");
        return session.getUser().getId();
    }
}
