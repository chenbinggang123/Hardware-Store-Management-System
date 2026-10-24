package com.example.demo.agent.api;

import com.example.demo.agent.harness.AgentRunResult;
import com.example.demo.agent.model.AgentModelGateway;
import com.example.demo.agent.service.AgentAttachmentService;
import com.example.demo.agent.service.AgentStreamingService;
import com.example.demo.billing.BillingContent;
import com.example.demo.billing.BillingService;
import com.example.demo.common.ApiResponse;
import com.example.demo.config.AuthInterceptor;
import com.example.demo.security.AuthSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 开单页的“拍照识别”：用户先选好单据类型再拍照，不经过聊天、不靠关键词猜类型。
 * 识别仍复用小五的识别流程（异步运行，开单页轮询结果），结果只是一张待核对的草稿。
 */
@RestController
@RequestMapping("/billing")
public class BillingRecognitionController {
    private static final Map<String, String> TASKS = Map.of(
            "PURCHASE", "录入客户拿货单，创建编辑草稿",
            "RETURN", "录入客户退货单，创建编辑草稿",
            "PROCUREMENT", "录入采购进货单，创建编辑草稿");

    private final ObjectProvider<AgentModelGateway> gatewayProvider;
    private final AgentAttachmentService attachmentService;
    private final AgentStreamingService streamingService;
    private final BillingService billing;
    private final com.example.demo.agent.service.BillingPhotoService photos;

    public BillingRecognitionController(ObjectProvider<AgentModelGateway> gatewayProvider,
                                        AgentAttachmentService attachmentService,
                                        AgentStreamingService streamingService,
                                        BillingService billing,
                                        com.example.demo.agent.service.BillingPhotoService photos) {
        this.gatewayProvider = gatewayProvider;
        this.attachmentService = attachmentService;
        this.streamingService = streamingService;
        this.billing = billing;
        this.photos = photos;
    }

    /** 商品详情：这个商品出现过的单据原图，带商品所在那一行的位置。 */
    @GetMapping("/product-photos")
    public ApiResponse<List<Map<String, Object>>> productPhotos(@org.springframework.web.bind.annotation.RequestParam Long productId,
                                                                HttpServletRequest httpRequest) {
        currentUserId(httpRequest);
        return ApiResponse.ok(photos.forProduct(productId));
    }

    /** 放弃一张没有入账的原图（重拍、取消识别、离开开单页）：已入账的原图不会被删。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/photos/{attachmentId}")
    public ApiResponse<Map<String, Object>> discardPhoto(@PathVariable Long attachmentId, HttpServletRequest httpRequest) {
        List<String> files = photos.discard(List.of(attachmentId), currentUserId(httpRequest));
        return ApiResponse.ok(Map.of("deleted", !files.isEmpty(), "cloudFiles", files));
    }

    /** 开单记录页打开时顺带清理：14 天还没入账的原图不再保留。 */
    @PostMapping("/photos/cleanup")
    public ApiResponse<Map<String, Object>> cleanupPhotos(HttpServletRequest httpRequest) {
        return ApiResponse.ok(Map.of("cloudFiles", photos.discardStale(currentUserId(httpRequest), 14)));
    }

    public record RecognitionRequest(Long attachmentId, String documentType) { }

    @PostMapping("/recognitions")
    public ApiResponse<AgentRunResult> recognize(@RequestBody RecognitionRequest request, HttpServletRequest httpRequest) {
        AgentModelGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) throw new IllegalArgumentException("识别服务尚未启用");
        if (request == null || request.attachmentId() == null) throw new IllegalArgumentException("请先拍照");
        String type = request.documentType() == null ? "PURCHASE" : request.documentType().trim().toUpperCase();
        String task = TASKS.get(type);
        if (task == null) throw new IllegalArgumentException("请选择拿货单、退货单或采购单");
        Long operatorId = currentUserId(httpRequest);
        List<Long> ids = List.of(request.attachmentId());
        Long conversationId = attachmentService.resolveConversationId(null, ids, operatorId);
        String displayInput = "拍照开单：" + task.substring(2, task.indexOf('，'));
        String modelInput = attachmentService.enrichPrompt(task + " [拍照开单:" + type + "]", ids, conversationId, operatorId);
        AgentRunResult result = streamingService.start(displayInput, modelInput, operatorId, gateway, conversationId);
        attachmentService.bindToRun(ids, result.getRunId(), result.getConversationId(), operatorId);
        return ApiResponse.ok("已开始识别", result);
    }

    /** 开单页显示原图用：只返回本人上传的原图和 OCR 行位置。 */
    @GetMapping("/documents/{id}/source")
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> source(@PathVariable Long id,
                                                   @org.springframework.web.bind.annotation.RequestParam(required = false) Long attachmentId,
                                                   HttpServletRequest httpRequest) {
        Long operatorId = currentUserId(httpRequest);
        Object content = billing.get(operatorId, id).get("content");
        Long saved = content instanceof BillingContent c ? c.getSourceAttachmentId()
                : content instanceof Map<?, ?> map && map.get("sourceAttachmentId") instanceof Number n ? n.longValue() : null;
        // 草稿还没记下原图时（普通识别流程），允许开单页带上刚上传的原图 ID；billingSource 仍会校验是本人的。
        if (saved != null) attachmentId = saved;
        if (attachmentId == null) return ApiResponse.ok(Map.of());
        return ApiResponse.ok(attachmentService.billingSource(attachmentId, operatorId));
    }

    private Long currentUserId(HttpServletRequest request) {
        Object value = request.getAttribute(AuthInterceptor.AUTH_SESSION_ATTRIBUTE);
        if (!(value instanceof AuthSession session) || session.getUser() == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return session.getUser().getId();
    }
}
