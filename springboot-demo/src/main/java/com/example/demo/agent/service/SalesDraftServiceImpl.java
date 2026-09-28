package com.example.demo.agent.service;

import com.example.demo.agent.dto.CommitSalesDraftRequest;
import com.example.demo.agent.dto.SalesDraftItemRequest;
import com.example.demo.agent.dto.SalesDraftLinePreview;
import com.example.demo.agent.dto.SalesDraftPreview;
import com.example.demo.agent.dto.SalesDraftRequest;
import com.example.demo.agent.entity.SalesOrderDraft;
import com.example.demo.agent.repository.SalesOrderDraftRepository;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.SalesOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class SalesDraftServiceImpl implements SalesDraftService {
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_COMMITTED = "COMMITTED";

    private final SalesOrderDraftRepository draftRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderService salesOrderService;

    public SalesDraftServiceImpl(
            SalesOrderDraftRepository draftRepository,
            CustomerRepository customerRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            SalesOrderRepository salesOrderRepository,
            SalesOrderService salesOrderService) {
        this.draftRepository = draftRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderService = salesOrderService;
    }

    @Override
    public SalesDraftPreview createDraft(SalesDraftRequest request, Long operatorId) {
        SalesOrderDraft draft = new SalesOrderDraft();
        draft.setOperatorId(requireOperator(operatorId));
        draft.setStatus(STATUS_DRAFT);
        draft.setCreateTime(LocalDateTime.now());
        draft.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        applyRequest(draft, request);
        return toPreview(draftRepository.save(draft));
    }

    @Override
    public SalesDraftPreview updateDraft(Long draftId, SalesDraftRequest request, Long operatorId) {
        SalesOrderDraft draft = requireOwnedDraft(draftId, operatorId);
        requireEditable(draft);
        requireExpectedVersion(draft, request);
        applyRequest(draft, request);
        return toPreview(draftRepository.save(draft));
    }

    @Override
    @Transactional(readOnly = true)
    public SalesDraftPreview previewUpdateDraft(Long draftId, SalesDraftRequest request, Long operatorId) {
        SalesOrderDraft current = requireOwnedDraft(draftId, operatorId);
        requireEditable(current);
        requireExpectedVersion(current, request);
        SalesOrderDraft preview = new SalesOrderDraft();
        preview.setId(current.getId());
        preview.setVersion(current.getVersion());
        preview.setOperatorId(current.getOperatorId());
        preview.setStatus(current.getStatus());
        preview.setCreateTime(current.getCreateTime());
        preview.setExpiresAt(current.getExpiresAt());
        applyRequest(preview, request);
        return toPreview(preview);
    }

    @Override
    @Transactional(readOnly = true)
    public SalesDraftPreview getDraft(Long draftId, Long operatorId) {
        return toPreview(requireOwnedDraft(draftId, operatorId));
    }

    @Override
    public SalesOrder commitDraft(Long draftId, CommitSalesDraftRequest request, Long operatorId) {
        if (request == null || !StringUtils.hasText(request.getIdempotencyKey())) {
            throw new IllegalArgumentException("幂等键不能为空");
        }

        SalesOrderDraft draft = requireOwnedDraft(draftId, operatorId);
        if (STATUS_COMMITTED.equals(draft.getStatus())) {
            return requireCommittedOrder(draft);
        }
        requireEditable(draft);
        if (request.getDraftVersion() == null || !request.getDraftVersion().equals(draft.getVersion())) {
            throw new IllegalArgumentException("草稿已发生变化，请刷新后重新确认");
        }

        SalesOrderDraft previous = draftRepository.findByCommitIdempotencyKey(request.getIdempotencyKey()).orElse(null);
        if (previous != null) {
            if (!previous.getId().equals(draftId)) {
                throw new IllegalArgumentException("幂等键已用于其他操作");
            }
            return requireCommittedOrder(previous);
        }

        SalesDraftPreview latestPreview = toPreview(draft);
        if (!latestPreview.isInventorySufficient()) {
            throw new IllegalArgumentException("部分商品库存不足，无法确认销售单");
        }

        SalesOrder order = new SalesOrder();
        order.setCustomerId(draft.getCustomerId());
        order.setOperatorId(draft.getOperatorId());
        order.setItems(copyItems(draft.getItems()));
        order.setReceivedAmount(draft.getReceivedAmount());
        SalesOrder savedOrder = salesOrderService.saveSalesOrder(order);

        draft.setCommitIdempotencyKey(request.getIdempotencyKey());
        draft.setCommittedOrderId(savedOrder.getId());
        draft.setStatus(STATUS_COMMITTED);
        draft.setUpdateTime(LocalDateTime.now());
        draftRepository.save(draft);
        return savedOrder;
    }

    private void applyRequest(SalesOrderDraft draft, SalesDraftRequest request) {
        if (request == null || request.getCustomerId() == null) {
            throw new IllegalArgumentException("客户不能为空");
        }
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("销售商品不能为空");
        }

        List<SalesOrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SalesDraftItemRequest itemRequest : request.getItems()) {
            if (itemRequest == null || itemRequest.getProductId() == null) {
                throw new IllegalArgumentException("商品不能为空");
            }
            if (itemRequest.getQuantity() == null || itemRequest.getQuantity() <= 0) {
                throw new IllegalArgumentException("商品数量必须大于 0");
            }
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + itemRequest.getProductId()));
            if (product.getStatus() != null && product.getStatus() != 1) {
                throw new IllegalArgumentException("商品已下架：" + product.getName());
            }
            BigDecimal price = itemRequest.getPrice();
            if (price == null) {
                price = findLastPrice(customer.getId(), product.getId());
            }
            if (price == null) {
                price = resolveDefaultPrice(product, customer);
            }
            if (price == null || price.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("商品价格无效：" + product.getName());
            }

            SalesOrderItem item = new SalesOrderItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setQuantity(itemRequest.getQuantity());
            item.setPrice(price);
            item.setAmount(price.multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
            total = total.add(item.getAmount());
            items.add(item);
        }

        BigDecimal received = nullSafe(request.getReceivedAmount());
        if (received.compareTo(BigDecimal.ZERO) < 0 || received.compareTo(total) > 0) {
            throw new IllegalArgumentException("已收金额必须在 0 和订单总额之间");
        }
        draft.setCustomerId(customer.getId());
        draft.setItems(items);
        draft.setTotalAmount(total);
        draft.setReceivedAmount(received);
        draft.setDebtAmount(total.subtract(received));
        draft.setUpdateTime(LocalDateTime.now());
    }

    private SalesDraftPreview toPreview(SalesOrderDraft draft) {
        Customer customer = customerRepository.findById(draft.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("客户不存在"));
        List<SalesDraftLinePreview> lines = new ArrayList<>();
        boolean allSufficient = true;
        for (SalesOrderItem item : safeItems(draft.getItems())) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + item.getProductId()));
            int available = inventoryRepository.findByProductId(product.getId())
                    .map(Inventory::getQuantity)
                    .orElse(0);
            boolean sufficient = available >= defaultInteger(item.getQuantity());
            allSufficient = allSufficient && sufficient;
            lines.add(new SalesDraftLinePreview(
                    product.getId(), product.getName(), product.getSpec(), item.getQuantity(), available,
                    item.getPrice(), item.getAmount(), sufficient));
        }
        BigDecimal previousDebt = nullSafe(customer.getDebt());
        return new SalesDraftPreview(
                draft.getId(), draft.getVersion(), draft.getStatus(), customer.getId(), customer.getName(),
                previousDebt, draft.getTotalAmount(), draft.getReceivedAmount(), draft.getDebtAmount(),
                previousDebt.add(nullSafe(draft.getDebtAmount())), allSufficient, lines, draft.getExpiresAt());
    }

    private BigDecimal findLastPrice(Long customerId, Long productId) {
        return salesOrderRepository.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(SalesOrder::getOrderTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .flatMap(order -> safeItems(order.getItems()).stream())
                .filter(item -> productId.equals(item.getProductId()))
                .map(SalesOrderItem::getPrice)
                .filter(price -> price != null)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal resolveDefaultPrice(Product product, Customer customer) {
        if (customer != null && "B".equalsIgnoreCase(customer.getType())) {
            return product.getWholesalePrice() == null ? product.getRetailPrice() : product.getWholesalePrice();
        }
        if (customer != null && "老客户".equalsIgnoreCase(customer.getType())) {
            return product.getOldCustomerPrice() == null ? product.getRetailPrice() : product.getOldCustomerPrice();
        }
        return product.getRetailPrice();
    }

    private SalesOrderDraft requireOwnedDraft(Long draftId, Long operatorId) {
        SalesOrderDraft draft = draftRepository.findById(draftId)
                .orElseThrow(() -> new IllegalArgumentException("销售草稿不存在"));
        if (!requireOperator(operatorId).equals(draft.getOperatorId())) {
            throw new IllegalArgumentException("无权访问该销售草稿");
        }
        return draft;
    }

    private void requireEditable(SalesOrderDraft draft) {
        if (!STATUS_DRAFT.equals(draft.getStatus())) {
            throw new IllegalArgumentException("销售草稿当前不可编辑");
        }
        if (draft.getExpiresAt() == null || draft.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("销售草稿已过期，请重新创建");
        }
    }

    private void requireExpectedVersion(SalesOrderDraft draft, SalesDraftRequest request) {
        if (request != null && request.getDraftVersion() != null
                && !request.getDraftVersion().equals(draft.getVersion())) {
            throw new IllegalArgumentException("销售草稿已发生变化，请刷新后重新修改");
        }
    }

    private SalesOrder requireCommittedOrder(SalesOrderDraft draft) {
        if (draft.getCommittedOrderId() == null) {
            throw new IllegalArgumentException("销售草稿状态异常，请联系管理员");
        }
        return salesOrderRepository.findById(draft.getCommittedOrderId())
                .orElseThrow(() -> new IllegalArgumentException("已提交的销售单不存在"));
    }

    private Long requireOperator(Long operatorId) {
        if (operatorId == null) {
            throw new IllegalArgumentException("操作人不能为空");
        }
        return operatorId;
    }

    private List<SalesOrderItem> copyItems(List<SalesOrderItem> source) {
        List<SalesOrderItem> result = new ArrayList<>();
        for (SalesOrderItem item : safeItems(source)) {
            SalesOrderItem copy = new SalesOrderItem();
            copy.setProductId(item.getProductId());
            copy.setProductName(item.getProductName());
            copy.setQuantity(item.getQuantity());
            copy.setPrice(item.getPrice());
            copy.setAmount(item.getAmount());
            result.add(copy);
        }
        return result;
    }

    private List<SalesOrderItem> safeItems(List<SalesOrderItem> items) {
        return items == null ? List.of() : items;
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }
}
