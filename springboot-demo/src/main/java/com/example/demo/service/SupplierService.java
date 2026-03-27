package com.example.demo.service;

import com.example.demo.entity.Supplier;
import java.util.List;
import java.util.Optional;

/**
 * 供应商业务逻辑接口
 */
public interface SupplierService {
    Supplier saveSupplier(Supplier supplier);
    Supplier updateSupplier(Supplier supplier);
    void deleteSupplier(Long id);
    Optional<Supplier> getSupplierById(Long id);
    List<Supplier> getAllSuppliers();
}