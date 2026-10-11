package com.example.demo.agent.service;

import com.example.demo.agent.dto.SourcePurchaseOrderRequest;
import com.example.demo.agent.entity.AgentAttachment;
import com.example.demo.agent.entity.SourcePurchaseDocument;
import com.example.demo.agent.entity.SourcePurchaseItem;
import com.example.demo.agent.repository.AgentAttachmentRepository;
import com.example.demo.agent.repository.SourcePurchaseDocumentRepository;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.PurchaseOrderItem;
import com.example.demo.entity.Supplier;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SupplierRepository;
import com.example.demo.service.PurchaseOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional
public class SourcePurchaseOrderService {
    private final SourcePurchaseDocumentRepository documentRepository;
    private final AgentAttachmentRepository attachmentRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final PurchaseOrderService purchaseOrderService;
    private final OperationLogRepository operationLogRepository;

    public SourcePurchaseOrderService(
            SourcePurchaseDocumentRepository documentRepository,
            AgentAttachmentRepository attachmentRepository,
            SupplierRepository supplierRepository,
            ProductRepository productRepository,
            PurchaseOrderService purchaseOrderService,
            OperationLogRepository operationLogRepository) {
        this.documentRepository = documentRepository;
        this.attachmentRepository = attachmentRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.purchaseOrderService = purchaseOrderService;
        this.operationLogRepository = operationLogRepository;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(
            Long runId,
            Long operatorId,
            SourcePurchaseOrderRequest request) {
        return previewView(prepare(runId, operatorId, request));
    }

    public Map<String, Object> save(
            Long runId,
            Long operatorId,
            SourcePurchaseOrderRequest request) {
        String idempotencyKey = "agent-run-" + runId + "-source-purchase";
        SourcePurchaseDocument existing = documentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) return documentView(existing);

        PreparedSourcePurchase prepared = prepare(runId, operatorId, request);
        PurchaseOrder purchaseOrder = null;
        if (prepared.canCreatePurchaseOrder()) {
            purchaseOrder = new PurchaseOrder();
            purchaseOrder.setSupplierId(prepared.supplier().getId());
            purchaseOrder.setOperatorId(operatorId);
            purchaseOrder.setOrderTime(prepared.orderDate().atStartOfDay());
            purchaseOrder.setStatus("待入库");
            purchaseOrder.setRemark(sourceRemark(prepared));
            purchaseOrder.setItems(toPurchaseItems(prepared.items()));
            purchaseOrder = purchaseOrderService.savePurchaseOrder(purchaseOrder);
            if (prepared.receiveStock()) purchaseOrder = purchaseOrderService.stockIn(purchaseOrder.getId());
        }

        SourcePurchaseDocument document = new SourcePurchaseDocument();
        document.setPurchaseOrderId(purchaseOrder == null ? null : purchaseOrder.getId());
        document.setAttachmentId(prepared.attachment().getId());
        document.setSupplierId(prepared.supplier() == null ? null : prepared.supplier().getId());
        document.setSupplierName(prepared.supplierName());
        document.setSourceOrderNo(prepared.sourceOrderNo());
        document.setOrderDate(prepared.orderDate());
        document.setBuyerName(trim(request.getBuyerName()));
        document.setHandlerName(trim(request.getHandlerName()));
        document.setDeclaredTotalAmount(money(request.getDeclaredTotalAmount()));
        document.setCalculatedTotalAmount(prepared.calculatedTotal());
        document.setReceiveStock(prepared.receiveStock());
        document.setRemark(trim(request.getRemark()));
        document.setOperatorId(operatorId);
        document.setIdempotencyKey(idempotencyKey);
        document.setCreateTime(LocalDateTime.now());
        document.setItems(prepared.items());
        SourcePurchaseDocument saved = documentRepository.save(document);
        audit(operatorId, "ARCHIVE", "保存源头厂商订单：" + saved.getSupplierName()
                + (StringUtils.hasText(saved.getSourceOrderNo()) ? " / " + saved.getSourceOrderNo() : ""));
        return documentView(saved);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(String keyword) {
        String normalized = normalize(keyword);
        return documentRepository.findAllByOrderByCreateTimeDesc().stream()
                .filter(item -> normalized.isEmpty()
                        || normalize(item.getSupplierName()).contains(normalized)
                        || normalize(item.getSourceOrderNo()).contains(normalized)
                        || normalize(item.getId() == null ? null : item.getId().toString()).contains(normalized))
                .limit(50)
                .map(this::documentView)
                .toList();
    }

    private PreparedSourcePurchase prepare(Long runId, Long operatorId, SourcePurchaseOrderRequest request) {
        if (request == null) throw new IllegalArgumentException("源头厂商订单不能为空");
        String supplierName = requiredText(request.getSupplierName(), "供应商名称");
        String sourceOrderNo = trim(request.getSourceOrderNo());
        if (StringUtils.hasText(sourceOrderNo)
                && documentRepository.findBySupplierNameIgnoreCaseAndSourceOrderNo(supplierName, sourceOrderNo).isPresent()) {
            throw new IllegalArgumentException("这家供应商的订单号已经保存过，请勿重复登记");
        }
        AgentAttachment attachment = resolveAttachment(runId, operatorId, request.getAttachmentId());
        Supplier supplier = exactSupplier(supplierName);
        List<Product> products = productRepository.findAll();
        List<String> warnings = new ArrayList<>();
        if (supplier == null) warnings.add("未唯一匹配到现有供应商，本次只保存原始凭证，不生成正式采购单");

        List<SourcePurchaseItem> items = normalizeItems(request.getItems(), products, warnings);
        if (items.isEmpty()) throw new IllegalArgumentException("请至少提供一行有效商品");
        BigDecimal calculatedTotal = items.stream().map(SourcePurchaseItem::getLineAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal declaredTotal = money(request.getDeclaredTotalAmount());
        if (declaredTotal != null && declaredTotal.subtract(calculatedTotal).abs().compareTo(new BigDecimal("0.01")) > 0) {
            warnings.add("单据合计与商品明细计算结果不一致，请重点核对");
        }
        boolean integerQuantities = items.stream().allMatch(item -> isInteger(item.getQuantity()));
        boolean allProductsMatched = items.stream().allMatch(item -> item.getProductId() != null);
        boolean canCreatePurchaseOrder = supplier != null && allProductsMatched && integerQuantities;
        boolean receiveStock = Boolean.TRUE.equals(request.getReceiveStock());
        if (receiveStock && !canCreatePurchaseOrder) {
            throw new IllegalArgumentException("同步入库前必须唯一匹配供应商、全部商品，且商品数量必须是整数");
        }
        if (!integerQuantities) warnings.add("存在非整数数量，本次只保存原始凭证，不生成正式采购单");
        if (!allProductsMatched) warnings.add("存在未匹配商品，本次只保存原始凭证，不生成正式采购单");
        return new PreparedSourcePurchase(
                attachment,
                supplier,
                supplierName,
                sourceOrderNo,
                request.getOrderDate() == null ? LocalDate.now() : request.getOrderDate(),
                items,
                calculatedTotal,
                receiveStock,
                canCreatePurchaseOrder,
                warnings);
    }

    private AgentAttachment resolveAttachment(Long runId, Long operatorId, Long requestedAttachmentId) {
        List<AgentAttachment> attachments = attachmentRepository.findByRunIdOrderByCreateTimeAsc(runId).stream()
                .filter(item -> operatorId.equals(item.getOperatorId())).toList();
        if (attachments.isEmpty()) throw new IllegalArgumentException("请先在当前消息中上传源头厂商订单附件");
        AgentAttachment attachment;
        if (requestedAttachmentId != null) {
            attachment = attachments.stream().filter(item -> requestedAttachmentId.equals(item.getId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("指定附件不属于当前 Agent 任务"));
        } else if (attachments.size() == 1) {
            attachment = attachments.get(0);
        } else {
            throw new IllegalArgumentException("当前消息有多个附件，请明确选择附件 ID");
        }
        if (!"PARSED".equals(attachment.getParseStatus())) {
            throw new IllegalArgumentException(attachment.getOriginalName() + " 尚未识别：" + attachment.getErrorMessage());
        }
        return attachment;
    }

    private Supplier exactSupplier(String supplierName) {
        List<Supplier> matches = supplierRepository.findAll().stream()
                .filter(item -> normalize(item.getName()).equals(normalize(supplierName))).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private List<SourcePurchaseItem> normalizeItems(
            List<SourcePurchaseOrderRequest.Item> requests,
            List<Product> products,
            List<String> warnings) {
        if (requests == null) return List.of();
        List<SourcePurchaseItem> result = new ArrayList<>();
        int fallbackLine = 0;
        for (SourcePurchaseOrderRequest.Item request : requests) {
            fallbackLine++;
            if (request == null) continue;
            String productName = requiredText(request.getProductName(), "第 " + fallbackLine + " 行商品名称");
            BigDecimal quantity = positive(request.getQuantity(), "第 " + fallbackLine + " 行数量");
            BigDecimal listPrice = nonNegative(request.getListUnitPrice(), "折前单价");
            BigDecimal discount = request.getDiscount();
            if (discount != null && (discount.compareTo(BigDecimal.ZERO) <= 0 || discount.compareTo(BigDecimal.ONE) > 0)) {
                throw new IllegalArgumentException("折扣必须大于 0 且不超过 1");
            }
            BigDecimal netPrice = nonNegative(request.getNetUnitPrice(), "折后单价");
            BigDecimal lineAmount = nonNegative(request.getLineAmount(), "行金额");
            if (netPrice == null && listPrice != null) {
                netPrice = listPrice.multiply(discount == null ? BigDecimal.ONE : discount);
            }
            if (netPrice == null && lineAmount != null) {
                netPrice = lineAmount.divide(quantity, 6, RoundingMode.HALF_UP);
            }
            if (netPrice == null) {
                netPrice = BigDecimal.ZERO;
                warnings.add("第 " + fallbackLine + " 行缺少价格，暂按 0 元保存");
            }
            if (lineAmount == null) lineAmount = netPrice.multiply(quantity);

            Product product = exactProduct(request.getBarcode(), productName, request.getSpecification(), products);
            SourcePurchaseItem item = new SourcePurchaseItem();
            item.setLineNo(request.getLineNo() == null ? fallbackLine : request.getLineNo());
            item.setProductId(product == null ? null : product.getId());
            item.setBarcode(trim(request.getBarcode()));
            item.setProductName(productName);
            item.setSpecification(trim(request.getSpecification()));
            item.setUnit(StringUtils.hasText(request.getUnit()) ? request.getUnit().trim() : "件");
            item.setQuantity(quantity.stripTrailingZeros());
            item.setListUnitPrice(money(listPrice));
            item.setDiscount(discount);
            item.setNetUnitPrice(money(netPrice));
            item.setLineAmount(money(lineAmount));
            item.setMatchStatus(product == null ? "UNMATCHED" : "MATCHED");
            item.setRemark(trim(request.getRemark()));
            result.add(item);
        }
        return result;
    }

    private Product exactProduct(String barcode, String name, String specification, List<Product> products) {
        if (StringUtils.hasText(barcode)) {
            List<Product> matches = products.stream()
                    .filter(item -> normalize(barcode).equals(normalize(item.getBarcode()))).toList();
            if (matches.size() == 1) return matches.get(0);
        }
        List<Product> matches = products.stream()
                .filter(item -> normalize(name).equals(normalize(item.getName())))
                .filter(item -> !StringUtils.hasText(specification)
                        || normalize(specification).equals(normalize(item.getSpec())))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private List<PurchaseOrderItem> toPurchaseItems(List<SourcePurchaseItem> items) {
        return items.stream().map(item -> {
            PurchaseOrderItem target = new PurchaseOrderItem();
            target.setProductId(item.getProductId());
            target.setProductName(item.getProductName());
            target.setQuantity(item.getQuantity().intValueExact());
            target.setPrice(item.getNetUnitPrice());
            target.setAmount(item.getLineAmount());
            return target;
        }).toList();
    }

    private String sourceRemark(PreparedSourcePurchase prepared) {
        String value = "源头厂商订单附件 #" + prepared.attachment().getId();
        if (StringUtils.hasText(prepared.sourceOrderNo())) value += "；厂商单号 " + prepared.sourceOrderNo();
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    private Map<String, Object> previewView(PreparedSourcePurchase prepared) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("attachmentId", prepared.attachment().getId());
        result.put("attachmentName", prepared.attachment().getOriginalName());
        result.put("supplierId", prepared.supplier() == null ? null : prepared.supplier().getId());
        result.put("supplierName", prepared.supplierName());
        result.put("sourceOrderNo", prepared.sourceOrderNo());
        result.put("orderDate", prepared.orderDate());
        result.put("items", prepared.items());
        result.put("calculatedTotalAmount", prepared.calculatedTotal());
        result.put("receiveStock", prepared.receiveStock());
        result.put("canCreatePurchaseOrder", prepared.canCreatePurchaseOrder());
        result.put("warnings", prepared.warnings());
        result.put("effect", prepared.canCreatePurchaseOrder()
                ? (prepared.receiveStock() ? "保存原始凭证、创建正式采购单并同步入库" : "保存原始凭证并创建待入库采购单")
                : "保存原始凭证；供应商或商品完成匹配后再生成正式采购单");
        return result;
    }

    private Map<String, Object> documentView(SourcePurchaseDocument document) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", document.getId());
        result.put("purchaseOrderId", document.getPurchaseOrderId());
        result.put("attachmentId", document.getAttachmentId());
        result.put("supplierId", document.getSupplierId());
        result.put("supplierName", document.getSupplierName());
        result.put("sourceOrderNo", document.getSourceOrderNo());
        result.put("orderDate", document.getOrderDate());
        result.put("buyerName", document.getBuyerName());
        result.put("handlerName", document.getHandlerName());
        result.put("declaredTotalAmount", document.getDeclaredTotalAmount());
        result.put("calculatedTotalAmount", document.getCalculatedTotalAmount());
        result.put("receiveStock", document.getReceiveStock());
        result.put("items", document.getItems());
        result.put("remark", document.getRemark());
        result.put("createdAt", document.getCreateTime());
        return result;
    }

    private BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(field + "必须大于 0");
        }
        return value;
    }

    private BigDecimal nonNegative(BigDecimal value, String field) {
        if (value != null && value.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + "不能小于 0");
        }
        return value;
    }

    private boolean isInteger(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() <= 0;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + "不能为空");
        return value.trim();
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void audit(Long operatorId, String action, String detail) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule("SOURCE_PURCHASE");
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }

    private record PreparedSourcePurchase(
            AgentAttachment attachment,
            Supplier supplier,
            String supplierName,
            String sourceOrderNo,
            LocalDate orderDate,
            List<SourcePurchaseItem> items,
            BigDecimal calculatedTotal,
            boolean receiveStock,
            boolean canCreatePurchaseOrder,
            List<String> warnings) {
    }
}
