package com.example.demo.billing;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Data
@Table(name="billing_product_memory", uniqueConstraints=@UniqueConstraint(columnNames={"operator_id","fingerprint"}))
public class BillingProductMemory {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="operator_id") private Long operatorId;
    private Long productId;
    @Column(length=64) private String fingerprint;
    private String name;
    private String specification;
    private String unit;
    private String alias;
    private BigDecimal lastPrice;
    private LocalDateTime updateTime;
}
