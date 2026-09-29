package com.example.demo.integration;

import com.example.demo.entity.Customer;
import com.example.demo.entity.Product;
import com.example.demo.service.CustomerService;
import com.example.demo.service.ProductService;
import com.example.demo.service.SupplierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MasterDataIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private SupplierService supplierService;

    @Test
    void duplicateBarcodeIsRejectedBeforeDatabaseConstraintFailure() {
        Product product = new Product();
        product.setName("重复条码测试商品");
        product.setBarcode("690100000001");
        product.setRetailPrice(BigDecimal.ONE);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> productService.saveProduct(product));

        assertTrue(exception.getMessage().contains("条码已存在"));
    }

    @Test
    void linkedCustomerAndSupplierCannotBeDeleted() {
        IllegalArgumentException customerException = assertThrows(
                IllegalArgumentException.class,
                () -> customerService.deleteCustomer(1L));
        assertTrue(customerException.getMessage().contains("销售订单"));

        IllegalArgumentException supplierException = assertThrows(
                IllegalArgumentException.class,
                () -> supplierService.deleteSupplier(1L));
        assertTrue(supplierException.getMessage().contains("采购订单"));
    }

    @Test
    void customerProfileUpdatePreservesAccountingDebt() {
        Customer before = customerService.getCustomerById(1L).orElseThrow();
        BigDecimal debtBefore = before.getDebt();

        Customer update = new Customer();
        update.setId(1L);
        update.setName(before.getName() + "-测试");
        update.setType(before.getType());
        update.setPhone(before.getPhone());
        update.setAddress(before.getAddress());
        update.setRemark(before.getRemark());
        update.setDebt(BigDecimal.ZERO);

        Customer saved = customerService.updateCustomer(update);

        assertEquals(debtBefore, saved.getDebt());
    }
}
