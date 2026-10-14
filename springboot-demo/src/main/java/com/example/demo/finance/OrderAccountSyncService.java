package com.example.demo.finance;

import com.example.demo.entity.Customer;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.Supplier;
import com.example.demo.finance.dto.AccountSyncPreview;
import com.example.demo.finance.entity.AccountDocument;
import com.example.demo.finance.entity.AccountPayment;
import com.example.demo.finance.entity.PaymentAllocation;
import com.example.demo.finance.repository.AccountDocumentRepository;
import com.example.demo.finance.repository.AccountPaymentRepository;
import com.example.demo.finance.repository.PaymentAllocationRepository;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.repository.SupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class OrderAccountSyncService {
    private final AccountDocumentRepository documentRepository;
    private final AccountPaymentRepository paymentRepository;
    private final PaymentAllocationRepository allocationRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;

    public OrderAccountSyncService(AccountDocumentRepository documentRepository,
                                   AccountPaymentRepository paymentRepository,
                                   PaymentAllocationRepository allocationRepository,
                                   SalesOrderRepository salesOrderRepository,
                                   PurchaseOrderRepository purchaseOrderRepository,
                                   CustomerRepository customerRepository,
                                   SupplierRepository supplierRepository) {
        this.documentRepository = documentRepository;
        this.paymentRepository = paymentRepository;
        this.allocationRepository = allocationRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.customerRepository = customerRepository;
        this.supplierRepository = supplierRepository;
    }

    public AccountDocument syncSalesOrder(SalesOrder order) {
        if (order == null || order.getId() == null || order.getOperatorId() == null || order.getCustomerId() == null) return null;
        Customer customer = customerRepository.findById(order.getCustomerId()).orElse(null);
        if (customer == null) return null;
        return sync(order.getOperatorId(), "SALES_ORDER", order.getId(), "RECEIVABLE", "CUSTOMER",
                customer.getId(), customer.getName(), order.getOrderNumber(),
                order.getOrderTime() == null ? LocalDate.now() : order.getOrderTime().toLocalDate(),
                order.getTotalAmount(), order.getReceivedAmount());
    }

    public AccountDocument syncPurchaseOrder(PurchaseOrder order) {
        if (order == null || order.getId() == null || order.getOperatorId() == null || order.getSupplierId() == null) return null;
        Supplier supplier = supplierRepository.findById(order.getSupplierId()).orElse(null);
        if (supplier == null) return null;
        return sync(order.getOperatorId(), "PURCHASE_ORDER", order.getId(), "PAYABLE", "SUPPLIER",
                supplier.getId(), supplier.getName(), order.getOrderNumber(),
                order.getOrderTime() == null ? LocalDate.now() : order.getOrderTime().toLocalDate(),
                order.getTotalAmount(), BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public AccountSyncPreview preview(Long operatorId) {
        List<SalesOrder> sales = salesOrderRepository.findAll().stream().filter(item -> operatorId.equals(item.getOperatorId())).toList();
        List<PurchaseOrder> purchases = purchaseOrderRepository.findAll().stream().filter(item -> operatorId.equals(item.getOperatorId())).toList();
        int synced = (int) sales.stream().filter(item -> exists(operatorId, "SALES_ORDER", item.getId())).count()
                + (int) purchases.stream().filter(item -> exists(operatorId, "PURCHASE_ORDER", item.getId())).count();
        return new AccountSyncPreview(sales.size(), sumSales(sales), purchases.size(), sumPurchases(purchases), synced);
    }

    public AccountSyncPreview syncHistory(Long operatorId) {
        AccountSyncPreview preview = preview(operatorId);
        salesOrderRepository.findAll().stream().filter(item -> operatorId.equals(item.getOperatorId())).forEach(this::syncSalesOrder);
        purchaseOrderRepository.findAll().stream().filter(item -> operatorId.equals(item.getOperatorId())).forEach(this::syncPurchaseOrder);
        return preview;
    }

    private AccountDocument sync(Long operatorId, String sourceType, Long sourceId, String direction,
                                 String counterpartyType, Long counterpartyId, String counterpartyName,
                                 String documentNumber, LocalDate date, BigDecimal totalValue, BigDecimal settledValue) {
        BigDecimal total = money(totalValue);
        if (total.signum() <= 0) return null;
        AccountDocument document = documentRepository
                .findByOperatorIdAndBusinessSourceTypeAndBusinessSourceId(operatorId, sourceType, sourceId)
                .orElseGet(AccountDocument::new);
        BigDecimal beforeSettled = money(document.getSettledAmount());
        BigDecimal desiredSettled = money(settledValue).min(total);
        document.setOperatorId(operatorId);
        document.setDirection(direction);
        document.setCounterpartyType(counterpartyType);
        document.setCounterpartyId(counterpartyId);
        document.setCounterpartyName(counterpartyName);
        document.setDocumentNumber(documentNumber);
        document.setDocumentDate(date);
        document.setTotalAmount(total);
        document.setBusinessSourceType(sourceType);
        document.setBusinessSourceId(sourceId);
        document.setNote("由" + ("SALES_ORDER".equals(sourceType) ? "销售单" : "采购单") + "自动同步");
        document.setCreateTime(document.getCreateTime() == null ? LocalDateTime.now() : document.getCreateTime());
        document.setUpdateTime(LocalDateTime.now());
        document.setSettledAmount(desiredSettled.max(beforeSettled).min(total));
        document.setOutstandingAmount(money(total.subtract(document.getSettledAmount())));
        document.setStatus(status(total, document.getSettledAmount()));
        document = documentRepository.save(document);
        BigDecimal paymentDelta = desiredSettled.subtract(beforeSettled);
        if (paymentDelta.signum() > 0) addPayment(document, paymentDelta, date);
        return document;
    }

    private void addPayment(AccountDocument document, BigDecimal amount, LocalDate date) {
        AccountPayment payment = new AccountPayment();
        payment.setOperatorId(document.getOperatorId());
        payment.setDirection("RECEIVE");
        payment.setCounterpartyType(document.getCounterpartyType());
        payment.setCounterpartyId(document.getCounterpartyId());
        payment.setCounterpartyName(document.getCounterpartyName());
        payment.setPaymentDate(date);
        payment.setAmount(amount);
        payment.setUnallocatedAmount(BigDecimal.ZERO.setScale(2));
        payment.setPaymentMethod("订单同步");
        payment.setNote("从销售单已收金额同步");
        payment.setCreateTime(LocalDateTime.now());
        payment = paymentRepository.save(payment);
        PaymentAllocation allocation = new PaymentAllocation();
        allocation.setPaymentId(payment.getId());
        allocation.setDocumentId(document.getId());
        allocation.setAmount(amount);
        allocation.setCreateTime(LocalDateTime.now());
        allocationRepository.save(allocation);
    }

    private boolean exists(Long operatorId, String type, Long id) {
        return documentRepository.findByOperatorIdAndBusinessSourceTypeAndBusinessSourceId(operatorId, type, id).isPresent();
    }

    private BigDecimal sumSales(List<SalesOrder> items) { return money(items.stream().map(item -> money(item.getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add)); }
    private BigDecimal sumPurchases(List<PurchaseOrder> items) { return money(items.stream().map(item -> money(item.getTotalAmount())).reduce(BigDecimal.ZERO, BigDecimal::add)); }
    private BigDecimal money(BigDecimal value) { return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP); }
    private String status(BigDecimal total, BigDecimal settled) { return settled.signum() == 0 ? "UNPAID" : settled.compareTo(total) >= 0 ? "SETTLED" : "PARTIAL"; }
}
