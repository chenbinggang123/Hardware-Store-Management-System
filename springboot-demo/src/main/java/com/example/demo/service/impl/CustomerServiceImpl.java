package com.example.demo.service.impl;

import com.example.demo.entity.AccountRecord;
import com.example.demo.entity.Customer;
import com.example.demo.entity.OperationLog;
import com.example.demo.entity.SalesOrder;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.service.CustomerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 客户业务逻辑实现类
 */
@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final OperationLogRepository operationLogRepository;

    public CustomerServiceImpl(
            CustomerRepository customerRepository,
            SalesOrderRepository salesOrderRepository,
            OperationLogRepository operationLogRepository) {
        this.customerRepository = customerRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.operationLogRepository = operationLogRepository;
    }

    @Override
    public Customer saveCustomer(Customer customer) {
        validateCustomer(customer);
        if (customer.getCreateTime() == null) {
            customer.setCreateTime(LocalDateTime.now());
        }
        if (customer.getDebt() == null) {
            customer.setDebt(BigDecimal.ZERO);
        }
        Customer saved = customerRepository.save(customer);
        saveOperationLog("CUSTOMER", "CREATE", "新增客户：" + saved.getName(), 1L);
        return saved;
    }

    @Override
    public Customer updateCustomer(Customer customer) {
        validateCustomer(customer);
        Customer existingCustomer = getCustomerById(customer.getId())
                .orElseThrow(() -> new IllegalArgumentException("客户不存在，无法更新"));
        existingCustomer.setName(customer.getName());
        existingCustomer.setType(customer.getType());
        existingCustomer.setPhone(customer.getPhone());
        existingCustomer.setAddress(customer.getAddress());
        existingCustomer.setRemark(customer.getRemark());
        Customer saved = customerRepository.save(existingCustomer);
        saveOperationLog("CUSTOMER", "UPDATE", "更新客户：" + saved.getName(), 1L);
        return saved;
    }

    @Override
    public void deleteCustomer(Long id) {
        Customer customer = getCustomerById(id)
                .orElseThrow(() -> new IllegalArgumentException("客户不存在，无法删除"));
        if (!salesOrderRepository.findByCustomerId(id).isEmpty()) {
            throw new IllegalArgumentException("客户存在销售订单，不允许删除");
        }
        customerRepository.deleteById(id);
        saveOperationLog("CUSTOMER", "DELETE", "删除客户：" + customer.getName(), 1L);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Customer> getCustomerById(Long id) {
        return customerRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Customer> getAllCustomers(String keyword, String type) {
        return customerRepository.findAll().stream()
                .filter(customer -> matchesKeyword(customer, keyword))
                .filter(customer -> !StringUtils.hasText(type) || type.equalsIgnoreCase(customer.getType()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesOrder> getSalesOrdersByCustomer(Long customerId) {
        return salesOrderRepository.findByCustomerId(customerId).stream()
                .sorted(Comparator.comparing(SalesOrder::getOrderTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountRecord> getAccountsByCustomer(Long customerId) {
        return getSalesOrdersByCustomer(customerId).stream()
                .filter(order -> order.getDebtAmount() != null && order.getDebtAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(order -> {
                    AccountRecord record = new AccountRecord();
                    record.setCustomerId(customerId);
                    record.setOrderId(order.getId());
                    record.setOrderNumber(order.getOrderNumber());
                    record.setTotalAmount(order.getTotalAmount());
                    record.setReceivedAmount(order.getReceivedAmount());
                    record.setDebtAmount(order.getDebtAmount());
                    record.setStatus(order.getPayStatus());
                    record.setCreateTime(order.getCreateTime());
                    return record;
                })
                .collect(Collectors.toList());
    }

    private boolean matchesKeyword(Customer customer, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalized = keyword.trim().toLowerCase();
        return containsText(customer.getName(), normalized)
                || containsText(customer.getPhone(), normalized);
    }

    private void validateCustomer(Customer customer) {
        if (customer == null || !StringUtils.hasText(customer.getName())) {
            throw new IllegalArgumentException("客户名称不能为空");
        }
        if (customer.getDebt() != null && customer.getDebt().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("客户欠款不能小于 0");
        }
        customer.setName(customer.getName().trim());
    }

    private boolean containsText(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase().contains(keyword);
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
