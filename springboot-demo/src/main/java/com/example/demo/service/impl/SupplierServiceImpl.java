package com.example.demo.service.impl;

import com.example.demo.entity.Supplier;
import com.example.demo.service.SupplierService;
import com.example.demo.service.impl.support.InMemoryCrudStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 供应商业务逻辑实现类
 */
@Service
public class SupplierServiceImpl implements SupplierService {

    private final InMemoryCrudStore<Supplier> store = new InMemoryCrudStore<>(Supplier::getId, Supplier::setId);

    @Autowired
    public SupplierServiceImpl() {
    }

    @Override
    public Supplier saveSupplier(Supplier supplier) {
        if (supplier.getCreateTime() == null) {
            supplier.setCreateTime(LocalDateTime.now());
        }
        return store.save(supplier);
    }

    @Override
    public Supplier updateSupplier(Supplier supplier) {
        Supplier existingSupplier = getSupplierById(supplier.getId())
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在，无法更新"));
        existingSupplier.setName(supplier.getName());
        existingSupplier.setContact(supplier.getContact());
        existingSupplier.setPhone(supplier.getPhone());
        existingSupplier.setAddress(supplier.getAddress());
        existingSupplier.setRemark(supplier.getRemark());
        return store.save(existingSupplier);
    }

    @Override
    public void deleteSupplier(Long id) {
        store.deleteById(id);
    }

    @Override
    public Optional<Supplier> getSupplierById(Long id) {
        return store.findById(id);
    }

    @Override
    public List<Supplier> getAllSuppliers() {
        return store.findAll();
    }
}
