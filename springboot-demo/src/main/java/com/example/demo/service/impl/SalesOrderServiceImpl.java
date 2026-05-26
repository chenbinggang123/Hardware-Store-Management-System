package com.example.demo.service.impl;

import com.example.demo.dto.PaymentRequest;
import com.example.demo.entity.Customer;
import com.example.demo.entity.Inventory;
import com.example.demo.entity.InventoryLog;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.Product;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.SalesOrderItem;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.InventoryLogRepository;
import com.example.demo.repository.InventoryRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.SalesOrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 销售订单业务逻辑实现类
 */
@Service
@Transactional
public class SalesOrderServiceImpl implements SalesOrderService {

    private final SalesOrderRepository salesOrderRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final CustomerRepository customerRepository;
    private final InventoryLogRepository inventoryLogRepository;
    private final OperationLogRepository operationLogRepository;

    public SalesOrderServiceImpl(
            SalesOrderRepository salesOrderRepository,
            ProductRepository productRepository,
            InventoryRepository inventoryRepository,
            CustomerRepository customerRepository,
            InventoryLogRepository inventoryLogRepository,
            OperationLogRepository operationLogRepository) {
        this.salesOrderRepository = salesOrderRepository;
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.customerRepository = customerRepository;
        this.inventoryLogRepository = inventoryLogRepository;
        this.operationLogRepository = operationLogRepository;
    }

    @Override
    public SalesOrder saveSalesOrder(SalesOrder salesOrder) {
        normalizeSalesOrder(salesOrder, false);
        SalesOrder saved = salesOrderRepository.save(salesOrder);
        recalculateCustomerDebt(saved.getCustomerId());
        saveOperationLog("SALES", "CREATE", "新建销售单：" + saved.getOrderNumber(), saved.getOperatorId());
        return saved;
    }

    @Override
    public SalesOrder updateSalesOrder(SalesOrder salesOrder) {
        SalesOrder existingOrder = getSalesOrderById(salesOrder.getId())
                .orElseThrow(() -> new IllegalArgumentException("销售单不存在，无法更新"));
        if ("已出库".equals(existingOrder.getStatus())) {
            throw new IllegalArgumentException("已出库销售单不允许修改");
        }
        existingOrder.setCustomerId(salesOrder.getCustomerId());
        existingOrder.setOperatorId(salesOrder.getOperatorId());
        existingOrder.setOrderTime(defaultDateTime(salesOrder.getOrderTime()));
        existingOrder.setItems(copySalesItems(salesOrder.getItems()));
        existingOrder.setReceivedAmount(salesOrder.getReceivedAmount());
        normalizeSalesOrder(existingOrder, true);
        SalesOrder saved = salesOrderRepository.save(existingOrder);
        recalculateCustomerDebt(saved.getCustomerId());
        saveOperationLog("SALES", "UPDATE", "更新销售单：" + saved.getOrderNumber(), saved.getOperatorId());
        return saved;
    }

    @Override
    public void deleteSalesOrder(Long id) {
        SalesOrder order = getSalesOrderById(id)
                .orElseThrow(() -> new IllegalArgumentException("销售单不存在，无法删除"));
        salesOrderRepository.deleteById(id);
        recalculateCustomerDebt(order.getCustomerId());
        saveOperationLog("SALES", "DELETE", "删除销售单：" + order.getOrderNumber(), order.getOperatorId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesOrder> getSalesOrderById(Long id) {
        return salesOrderRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesOrder> getAllSalesOrders(Long customerId, String status, String payStatus, String dateFrom, String dateTo) {
        LocalDate from = parseDate(dateFrom);
        LocalDate to = parseDate(dateTo);
        return salesOrderRepository.findAll().stream()
                .filter(order -> customerId == null || customerId.equals(order.getCustomerId()))
                .filter(order -> !StringUtils.hasText(status) || status.equals(order.getStatus()))
                .filter(order -> !StringUtils.hasText(payStatus) || payStatus.equals(order.getPayStatus()))
                .filter(order -> inDateRange(order.getOrderTime(), from, to))
                .sorted(Comparator.comparing(SalesOrder::getOrderTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Override
    public SalesOrder stockOut(Long id) {
        SalesOrder order = getSalesOrderById(id)
                .orElseThrow(() -> new IllegalArgumentException("销售单不存在，无法出库"));
        if ("已出库".equals(order.getStatus())) {
            return order;
        }
        for (SalesOrderItem item : safeSalesItems(order.getItems())) {
            Product product = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("商品不存在，无法出库"));
            Inventory inventory = findOrCreateInventory(product.getId(), product.getLocationId(), product.getStock());
            int beforeQuantity = defaultInteger(inventory.getQuantity());
            int outQuantity = defaultInteger(item.getQuantity());
            if (beforeQuantity < outQuantity) {
                throw new IllegalArgumentException("商品库存不足：" + product.getName());
            }
            int afterQuantity = beforeQuantity - outQuantity;
            inventory.setQuantity(afterQuantity);
            inventory.setLastUpdateTime(LocalDateTime.now());
            inventoryRepository.save(inventory);

            product.setStock(afterQuantity);
            productRepository.save(product);

            InventoryLog log = new InventoryLog();
            log.setProductId(product.getId());
            log.setProductName(product.getName());
            log.setChangeType("出库");
            log.setQuantity(outQuantity);
            log.setBeforeQuantity(beforeQuantity);
            log.setAfterQuantity(afterQuantity);
            log.setOperatorId(order.getOperatorId());
            log.setRelatedOrderId(order.getId());
            log.setRemark("销售单出库：" + order.getOrderNumber());
            log.setCreateTime(LocalDateTime.now());
            inventoryLogRepository.save(log);
        }
        order.setStatus("已出库");
        SalesOrder saved = salesOrderRepository.save(order);
        saveOperationLog("SALES", "STOCK_OUT", "销售单出库：" + saved.getOrderNumber(), saved.getOperatorId());
        return saved;
    }

    @Override
    public SalesOrder registerPayment(Long id, PaymentRequest paymentRequest) {
        SalesOrder order = getSalesOrderById(id)
                .orElseThrow(() -> new IllegalArgumentException("销售单不存在，无法登记收款"));
        BigDecimal currentReceived = nullSafe(order.getReceivedAmount());
        BigDecimal income = paymentRequest.getReceivedAmount() == null ? BigDecimal.ZERO : paymentRequest.getReceivedAmount();
        BigDecimal newReceived = currentReceived.add(income);
        if (newReceived.compareTo(order.getTotalAmount()) > 0) {
            newReceived = order.getTotalAmount();
        }
        order.setReceivedAmount(newReceived);
        order.setDebtAmount(order.getTotalAmount().subtract(newReceived).max(BigDecimal.ZERO));
        order.setPayStatus(resolvePayStatus(order.getDebtAmount(), order.getTotalAmount()));
        SalesOrder saved = salesOrderRepository.save(order);
        recalculateCustomerDebt(saved.getCustomerId());
        saveOperationLog("SALES", "PAYMENT",
                "销售单收款：" + saved.getOrderNumber() + "，方式：" + paymentRequest.getPaymentMethod(), saved.getOperatorId());
        return saved;
    }

    private void normalizeSalesOrder(SalesOrder salesOrder, boolean keepOrderNumber) {
        if (!keepOrderNumber || !StringUtils.hasText(salesOrder.getOrderNumber())) {
            salesOrder.setOrderNumber("SO" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now()));
        }
        salesOrder.setOrderTime(defaultDateTime(salesOrder.getOrderTime()));
        if (salesOrder.getCreateTime() == null) {
            salesOrder.setCreateTime(LocalDateTime.now());
        }
        if (!StringUtils.hasText(salesOrder.getStatus())) {
            salesOrder.setStatus("待出库");
        }
        List<SalesOrderItem> items = copySalesItems(salesOrder.getItems());
        Customer customer = customerRepository.findById(salesOrder.getCustomerId()).orElse(null);
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (SalesOrderItem item : items) {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (!StringUtils.hasText(item.getProductName()) && product != null) {
                item.setProductName(product.getName());
            }
            if (item.getPrice() == null) {
                item.setPrice(resolveSalesPrice(product, customer));
            }
            BigDecimal amount = item.getAmount();
            if (amount == null) {
                amount = item.getPrice().multiply(BigDecimal.valueOf(defaultInteger(item.getQuantity())));
            }
            item.setAmount(amount);
            totalAmount = totalAmount.add(amount);
        }
        salesOrder.setItems(items);
        salesOrder.setTotalAmount(totalAmount);
        salesOrder.setReceivedAmount(nullSafe(salesOrder.getReceivedAmount()));
        if (salesOrder.getReceivedAmount().compareTo(totalAmount) > 0) {
            salesOrder.setReceivedAmount(totalAmount);
        }
        salesOrder.setDebtAmount(totalAmount.subtract(salesOrder.getReceivedAmount()).max(BigDecimal.ZERO));
        salesOrder.setPayStatus(resolvePayStatus(salesOrder.getDebtAmount(), totalAmount));
    }

    private BigDecimal resolveSalesPrice(Product product, Customer customer) {
        if (product == null) {
            return BigDecimal.ZERO;
        }
        if (customer == null || !StringUtils.hasText(customer.getType())) {
            return nullSafe(product.getRetailPrice());
        }
        if ("B".equalsIgnoreCase(customer.getType())) {
            return nullSafe(product.getWholesalePrice());
        }
        if ("老客户".equalsIgnoreCase(customer.getType())) {
            return nullSafe(product.getOldCustomerPrice());
        }
        return nullSafe(product.getRetailPrice());
    }

    private String resolvePayStatus(BigDecimal debtAmount, BigDecimal totalAmount) {
        if (debtAmount.compareTo(BigDecimal.ZERO) == 0) {
            return "已付";
        }
        if (debtAmount.compareTo(totalAmount) == 0) {
            return "未付";
        }
        return "部分";
    }

    private void recalculateCustomerDebt(Long customerId) {
        if (customerId == null) {
            return;
        }
        customerRepository.findById(customerId).ifPresent(customer -> {
            BigDecimal debt = salesOrderRepository.findByCustomerId(customerId).stream()
                    .map(order -> nullSafe(order.getDebtAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            customer.setDebt(debt);
            customerRepository.save(customer);
        });
    }

    private Inventory findOrCreateInventory(Long productId, String fallbackLocation, Integer currentProductStock) {
        return inventoryRepository.findByProductId(productId)
                .orElseGet(() -> {
                    Inventory inventory = new Inventory();
                    inventory.setProductId(productId);
                    inventory.setQuantity(defaultInteger(currentProductStock));
                    inventory.setWarningThreshold(10);
                    inventory.setLocationId(fallbackLocation);
                    inventory.setLastUpdateTime(LocalDateTime.now());
                    return inventoryRepository.save(inventory);
                });
    }

    private List<SalesOrderItem> copySalesItems(List<SalesOrderItem> items) {
        return new ArrayList<>(safeSalesItems(items));
    }

    private List<SalesOrderItem> safeSalesItems(List<SalesOrderItem> items) {
        return items == null ? new ArrayList<>() : items;
    }

    private boolean inDateRange(LocalDateTime dateTime, LocalDate from, LocalDate to) {
        if (dateTime == null) {
            return true;
        }
        LocalDate date = dateTime.toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private LocalDate parseDate(String value) {
        return StringUtils.hasText(value) ? LocalDate.parse(value) : null;
    }

    private LocalDateTime defaultDateTime(LocalDateTime value) {
        return value == null ? LocalDateTime.now() : value;
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private void saveOperationLog(String module, String action, String detail, Long operatorId) {
        OperationLog log = new OperationLog();
        log.setOperatorId(operatorId);
        log.setModule(module);
        log.setAction(action);
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        operationLogRepository.save(log);
    }
}
