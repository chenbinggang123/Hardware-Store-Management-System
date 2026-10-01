package com.example.demo;

import com.example.demo.agent.dto.CommitSalesDraftRequest;
import com.example.demo.agent.dto.SalesDraftItemRequest;
import com.example.demo.agent.dto.SalesDraftPreview;
import com.example.demo.agent.dto.SalesDraftRequest;
import com.example.demo.agent.entity.SalesOrderDraft;
import com.example.demo.agent.entity.AgentRun;
import com.example.demo.agent.harness.AgentHarness;
import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.harness.AgentRunStatus;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.model.AgentModelResponse;
import com.example.demo.agent.model.AgentToolCall;
import com.example.demo.agent.policy.AgentPolicyEngine;
import com.example.demo.agent.repository.AgentRunRepository;
import com.example.demo.agent.repository.SalesOrderDraftRepository;
import com.example.demo.agent.service.SalesDraftServiceImpl;
import com.example.demo.agent.service.SalesDraftService;
import com.example.demo.agent.tool.AgentTool;
import com.example.demo.agent.tool.AgentToolContext;
import com.example.demo.agent.tool.AgentToolDefinition;
import com.example.demo.agent.tool.AgentToolRegistry;
import com.example.demo.agent.tool.AgentToolRisk;
import com.example.demo.agent.tool.builtin.CommitSalesDraftTool;
import com.example.demo.agent.trace.AgentTraceRecorder;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.SalesOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppTest {
    private SalesOrderDraftRepository draftRepository;
    private CustomerRepository customerRepository;
    private ProductRepository productRepository;
    private InventoryRepository inventoryRepository;
    private SalesOrderRepository salesOrderRepository;
    private SalesOrderService salesOrderService;
    private SalesDraftServiceImpl service;

    @BeforeEach
    void setUp() {
        draftRepository = mock(SalesOrderDraftRepository.class);
        customerRepository = mock(CustomerRepository.class);
        productRepository = mock(ProductRepository.class);
        inventoryRepository = mock(InventoryRepository.class);
        salesOrderRepository = mock(SalesOrderRepository.class);
        salesOrderService = mock(SalesOrderService.class);
        service = new SalesDraftServiceImpl(
                draftRepository,
                customerRepository,
                productRepository,
                inventoryRepository,
                salesOrderRepository,
                salesOrderService);
    }

    @Test
    void createsDraftPreviewFromBusinessData() {
        Customer customer = new Customer();
        customer.setId(10L);
        customer.setName("测试客户");
        customer.setType("零售");
        customer.setDebt(new BigDecimal("100.00"));

        Product product = new Product();
        product.setId(20L);
        product.setName("测试电钻");
        product.setSpec("20V");
        product.setRetailPrice(new BigDecimal("280.00"));
        product.setStatus(1);

        Inventory inventory = new Inventory();
        inventory.setProductId(20L);
        inventory.setQuantity(5);

        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer));
        when(productRepository.findById(20L)).thenReturn(Optional.of(product));
        when(inventoryRepository.findByProductId(20L)).thenReturn(Optional.of(inventory));
        when(salesOrderRepository.findByCustomerId(10L)).thenReturn(List.of());
        when(draftRepository.save(any(SalesOrderDraft.class))).thenAnswer(invocation -> {
            SalesOrderDraft draft = invocation.getArgument(0);
            draft.setId(1L);
            draft.setVersion(0L);
            return draft;
        });

        SalesDraftItemRequest item = new SalesDraftItemRequest();
        item.setProductId(20L);
        item.setQuantity(2);
        SalesDraftRequest request = new SalesDraftRequest();
        request.setCustomerId(10L);
        request.setReceivedAmount(BigDecimal.ZERO);
        request.setItems(List.of(item));

        SalesDraftPreview preview = service.createDraft(request, 1L);

        assertEquals(new BigDecimal("560.00"), preview.getTotalAmount());
        assertEquals(new BigDecimal("660.00"), preview.getDebtAfterCommit());
        assertEquals(5, preview.getItems().get(0).getAvailableQuantity());
        assertTrue(preview.isInventorySufficient());
    }

    @Test
    void repeatedCommitReturnsExistingOrder() {
        SalesOrderDraft draft = new SalesOrderDraft();
        draft.setId(1L);
        draft.setOperatorId(7L);
        draft.setStatus("COMMITTED");
        draft.setCommittedOrderId(99L);
        draft.setExpiresAt(LocalDateTime.now().plusMinutes(5));

        SalesOrder order = new SalesOrder();
        order.setId(99L);

        when(draftRepository.findById(1L)).thenReturn(Optional.of(draft));
        when(salesOrderRepository.findById(99L)).thenReturn(Optional.of(order));

        CommitSalesDraftRequest request = new CommitSalesDraftRequest();
        request.setDraftVersion(0L);
        request.setIdempotencyKey("commit-key-1");

        SalesOrder result = service.commitDraft(1L, request, 7L);

        assertEquals(99L, result.getId());
        verify(salesOrderService, never()).saveSalesOrder(any(SalesOrder.class));
    }

    @Test
    void updateDraftRejectsStaleVersion() {
        SalesOrderDraft draft = new SalesOrderDraft();
        draft.setId(2L);
        draft.setOperatorId(7L);
        draft.setStatus("DRAFT");
        draft.setVersion(4L);
        draft.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(draftRepository.findById(2L)).thenReturn(Optional.of(draft));

        SalesDraftRequest request = new SalesDraftRequest();
        request.setDraftVersion(3L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.updateDraft(2L, request, 7L));

        assertTrue(exception.getMessage().contains("发生变化"));
        verify(draftRepository, never()).save(any(SalesOrderDraft.class));
    }

    @Test
    void harnessExecutesReadOnlyToolAndCompletes() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentTraceRecorder traceRecorder = mock(AgentTraceRecorder.class);
        when(runRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(88L);
                run.setVersion(0L);
            }
            return run;
        });

        AgentTool echoTool = new AgentTool() {
            @Override
            public AgentToolDefinition definition() {
                return new AgentToolDefinition("lookup", "测试只读查询", AgentToolRisk.R0_READ_ONLY, Map.of());
            }

            @Override
            public Object execute(AgentToolContext context, Map<String, Object> arguments) {
                return Map.of("value", arguments.get("keyword"));
            }
        };

        AgentToolRegistry registry = new AgentToolRegistry(List.of(echoTool));
        AgentHarness harness = new AgentHarness(
                runRepository,
                registry,
                new AgentPolicyEngine(),
                traceRecorder,
                new ObjectMapper());

        AtomicInteger calls = new AtomicInteger();
        AgentModelGateway gateway = (context, tools) -> {
            if (calls.getAndIncrement() == 0) {
                return AgentModelResponse.tools(List.of(
                        new AgentToolCall("call-1", "lookup", Map.of("keyword", "电钻"))));
            }
            assertEquals("电钻", ((Map<?, ?>) context.getToolResults().get(0).get("result")).get("value"));
            return AgentModelResponse.finalAnswer("库存查询完成");
        };

        AgentRunResult result = harness.run("查一下电钻", 7L, gateway);

        assertEquals(88L, result.getRunId());
        assertEquals(AgentRunStatus.COMPLETED, result.getStatus());
        assertEquals("库存查询完成", result.getOutput());
        verify(traceRecorder).record(any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void harnessStopsRepeatedToolCall() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentTraceRecorder traceRecorder = mock(AgentTraceRecorder.class);
        when(runRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(89L);
            }
            return run;
        });
        AgentTool tool = new AgentTool() {
            public AgentToolDefinition definition() {
                return new AgentToolDefinition("lookup", "测试查询", AgentToolRisk.R0_READ_ONLY, Map.of());
            }

            public Object execute(AgentToolContext context, Map<String, Object> arguments) {
                return Map.of("ok", true);
            }
        };
        AgentHarness harness = new AgentHarness(
                runRepository,
                new AgentToolRegistry(List.of(tool)),
                new AgentPolicyEngine(),
                traceRecorder,
                new ObjectMapper());
        AgentModelGateway gateway = (context, tools) -> AgentModelResponse.tools(List.of(
                new AgentToolCall("call-repeat", "lookup", Map.of("keyword", "相同"))));

        AgentRunResult result = harness.run("重复调用测试", 7L, gateway);

        assertEquals(AgentRunStatus.FAILED, result.getStatus());
        assertTrue(result.getOutput().contains("重复工具调用"));
    }

    @Test
    void harnessPausesAndExecutesDraftToolAfterApproval() {
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        AgentTraceRecorder traceRecorder = mock(AgentTraceRecorder.class);
        AtomicReference<AgentRun> storedRun = new AtomicReference<>();
        when(runRepository.save(any(AgentRun.class))).thenAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            if (run.getId() == null) {
                run.setId(90L);
            }
            storedRun.set(run);
            return run;
        });
        when(runRepository.findById(90L)).thenAnswer(invocation -> Optional.of(storedRun.get()));
        when(runRepository.claimApproval(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            storedRun.get().setStatus(AgentRunStatus.RUNNING);
            return 1;
        });

        AtomicInteger executions = new AtomicInteger();
        AgentTool draftTool = new AgentTool() {
            public AgentToolDefinition definition() {
                return new AgentToolDefinition("create_sales_draft", "创建销售草稿",
                        AgentToolRisk.R1_DRAFT, Map.of(), "确认创建销售草稿");
            }

            public Object execute(AgentToolContext context, Map<String, Object> arguments) {
                executions.incrementAndGet();
                return Map.of("draftId", 12L);
            }
        };
        AgentHarness harness = new AgentHarness(
                runRepository,
                new AgentToolRegistry(List.of(draftTool)),
                new AgentPolicyEngine(),
                traceRecorder,
                new ObjectMapper());
        AgentModelGateway gateway = (context, tools) -> AgentModelResponse.tools(List.of(
                new AgentToolCall("draft-call", "create_sales_draft", Map.of(
                        "customerId", 10,
                        "items", List.of(Map.of("productId", 20, "quantity", 2))))));

        AgentRunResult waiting = harness.run("给客户开两把电钻", 7L, gateway);
        assertEquals(AgentRunStatus.WAITING_APPROVAL, waiting.getStatus());
        assertEquals("create_sales_draft", waiting.getPendingAction().getToolName());
        assertEquals(AgentToolRisk.R1_DRAFT, waiting.getPendingAction().getRisk());
        assertEquals(0, executions.get());

        AgentRunResult detail = harness.getRun(90L, 7L);
        assertEquals("确认创建销售草稿", detail.getPendingAction().getTitle());
        assertTrue(detail.getPendingAction().getPreview().toString().contains("customerId"));

        AgentModelGateway completionGateway = (context, tools) -> {
            assertEquals(1, context.getToolResults().size());
            return AgentModelResponse.finalAnswer("销售草稿 12 已创建，请核对后提交");
        };
        AgentRunResult completed = harness.resolveApproval(90L, 7L, true, completionGateway);
        assertEquals(AgentRunStatus.COMPLETED, completed.getStatus());
        assertEquals(1, executions.get());
        assertEquals("销售草稿 12 已创建，请核对后提交", completed.getOutput());
    }

    @Test
    void commitDraftToolBuildsPreviewAndOwnsIdempotencyKey() {
        SalesDraftService draftService = mock(SalesDraftService.class);
        SalesDraftPreview preview = new SalesDraftPreview(
                12L, 3L, "DRAFT", 10L, "测试客户",
                new BigDecimal("100.00"), new BigDecimal("560.00"), BigDecimal.ZERO,
                new BigDecimal("560.00"), new BigDecimal("660.00"), true,
                List.of(), LocalDateTime.now().plusMinutes(10));
        when(draftService.getDraft(12L, 7L)).thenReturn(preview);
        SalesOrder order = new SalesOrder();
        order.setId(99L);
        when(draftService.commitDraft(any(), any(), any())).thenReturn(order);

        CommitSalesDraftTool tool = new CommitSalesDraftTool(draftService);
        AgentToolContext context = new AgentToolContext(90L, 7L);
        Map<String, Object> arguments = Map.of("draftId", 12, "draftVersion", 3);

        Object confirmation = tool.approvalPreview(context, arguments);
        assertTrue(confirmation.toString().contains("660.00"));
        assertEquals(order, tool.execute(context, arguments));

        ArgumentCaptor<CommitSalesDraftRequest> requestCaptor =
                ArgumentCaptor.forClass(CommitSalesDraftRequest.class);
        verify(draftService).commitDraft(any(), requestCaptor.capture(), any());
        assertEquals("agent-run-90-commit-draft-12", requestCaptor.getValue().getIdempotencyKey());
        assertEquals(3L, requestCaptor.getValue().getDraftVersion());
    }
}
