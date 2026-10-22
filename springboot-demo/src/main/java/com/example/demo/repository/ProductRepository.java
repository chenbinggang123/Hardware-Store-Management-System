package com.example.demo.repository;

import com.example.demo.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

/**
 * 商品数据访问接口
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from Product p where lower(trim(p.name))=lower(:name) and lower(trim(coalesce(p.spec,'')))=lower(:spec) and lower(trim(coalesce(p.unit,'')))=lower(:unit) order by p.id")
    List<Product> lockIdentity(@org.springframework.data.repository.query.Param("name") String name, @org.springframework.data.repository.query.Param("spec") String spec, @org.springframework.data.repository.query.Param("unit") String unit);
    Optional<Product> findByBarcode(String barcode);
    List<Product> findTop10ByNameIgnoreCaseOrderByIdAsc(String name);
    List<Product> findTop10ByNameContainingIgnoreCaseOrderByIdAsc(String name);
}
