package com.example.demo.service;

import com.example.demo.entity.Customer;
import com.example.demo.entity.Product;
import com.example.demo.entity.PurchaseOrder;
import com.example.demo.entity.SalesOrder;
import com.example.demo.entity.Supplier;
import com.example.demo.repository.CustomerRepository;
import com.example.demo.repository.OperationLogRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.repository.PurchaseOrderRepository;
import com.example.demo.repository.SalesOrderRepository;
import com.example.demo.repository.SupplierRepository;
import com.example.demo.service.impl.CustomerServiceImpl;
import com.example.demo.service.impl.ProductServiceImpl;
import com.example.demo.service.impl.SupplierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MasterDataWorkflowTest {

    private ProductRepository productRepository;
    private CustomerRepository customerRepository;
    private SupplierRepository supplierRepository;
    private SalesOrderRepository salesOrderRepository;
    private PurchaseOrderRepository purchaseOrderRepository;
    private ProductServiceImpl productService;
    private CustomerServiceImpl customerService;
    private SupplierServiceImpl supplierService;

    @BeforeEach
    void setUp() {
        OperationLogRepository operationLogRepository = mock(OperationLogRepository.class);
        productRepository = mock(ProductRepository.class);
        customerRepository = mock(CustomerRepository.class);
        supplierRepository = mock(SupplierRepository.class);
        salesOrderRepository = mock(SalesOrderRepository.class);
        purchaseOrderRepository = mock(PurchaseOrderRepository.class);
        productService = new ProductServiceImpl(productRepository, operationLogRepository);
        customerService = new CustomerServiceImpl(
                customerRepository, salesOrderRepository, operationLogRepository);
        supplierService = new SupplierServiceImpl(
                supplierRepository, purchaseOrderRepository, operationLogRepository);
    }

    @Test
    void productRejectsBlankRequiredFieldsAndInvalidNumbers() {
        Product blankName = validProduct();
        blankName.setName(" ");
        assertThrows(IllegalArgumentException.class, () -> productService.saveProduct(blankName));

        Product blankBarcode = validProduct();
        blankBarcode.setBarcode("");
        assertThrows(IllegalArgumentException.class, () -> productService.saveProduct(blankBarcode));

        Product negativePrice = validProduct();
        negativePrice.setRetailPrice(new BigDecimal("-0.01"));
        assertThrows(IllegalArgumentException.class, () -> productService.saveProduct(negativePrice));

        Product negativeStock = validProduct();
        negativeStock.setStock(-1);
        assertThrows(IllegalArgumentException.class, () -> productService.saveProduct(negativeStock));

        Product invalidStatus = validProduct();
        invalidStatus.setStatus(2);
        assertThrows(IllegalArgumentException.class, () -> productService.saveProduct(invalidStatus));

        verify(productRepository, never()).save(any());
    }

    @Test
    void productRejectsDuplicateBarcodeOnCreateAndUpdate() {
        Product existing = validProduct();
        existing.setId(1L);
        when(productRepository.findByBarcode("690000000001")).thenReturn(Optional.of(existing));

        assertThrows(IllegalArgumentException.class, () -> productService.saveProduct(validProduct()));

        Product target = validProduct();
        target.setId(2L);
        when(productRepository.findById(2L)).thenReturn(Optional.of(target));
        Product update = validProduct();
        update.setId(2L);
        assertThrows(IllegalArgumentException.class, () -> productService.updateProduct(update));

        verify(productRepository, never()).save(any());
    }

    @Test
    void customerDebtCannotBeChangedThroughProfileUpdate() {
        Customer existing = customer(1L, "客户甲", new BigDecimal("120.00"));
        Customer update = customer(1L, "客户甲更新", BigDecimal.ZERO);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Customer saved = customerService.updateCustomer(update);

        assertEquals(new BigDecimal("120.00"), saved.getDebt());
    }

    @Test
    void customerWithSalesOrdersCannotBeDeleted() {
        Customer customer = customer(1L, "客户甲", BigDecimal.ZERO);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(salesOrderRepository.findByCustomerId(1L)).thenReturn(List.of(new SalesOrder()));

        assertThrows(IllegalArgumentException.class, () -> customerService.deleteCustomer(1L));

        verify(customerRepository, never()).deleteById(1L);
    }

    @Test
    void supplierWithPurchaseOrdersCannotBeDeleted() {
        Supplier supplier = new Supplier();
        supplier.setId(1L);
        supplier.setName("供应商甲");
        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));
        when(purchaseOrderRepository.findBySupplierId(1L)).thenReturn(List.of(new PurchaseOrder()));

        assertThrows(IllegalArgumentException.class, () -> supplierService.deleteSupplier(1L));

        verify(supplierRepository, never()).deleteById(1L);
    }

    @Test
    void customerAndSupplierRejectBlankName() {
        Customer customer = customer(null, " ", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> customerService.saveCustomer(customer));

        Supplier supplier = new Supplier();
        supplier.setName("");
        assertThrows(IllegalArgumentException.class, () -> supplierService.saveSupplier(supplier));

        verify(customerRepository, never()).save(any());
        verify(supplierRepository, never()).save(any());
    }

    private Product validProduct() {
        Product product = new Product();
        product.setName("测试商品");
        product.setBarcode("690000000001");
        product.setRetailPrice(new BigDecimal("10.00"));
        product.setWholesalePrice(new BigDecimal("9.00"));
        product.setOldCustomerPrice(new BigDecimal("8.50"));
        product.setCostPrice(new BigDecimal("7.00"));
        product.setStock(1);
        product.setStatus(1);
        return product;
    }

    private Customer customer(Long id, String name, BigDecimal debt) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        customer.setType("零售");
        customer.setDebt(debt);
        return customer;
    }
}
