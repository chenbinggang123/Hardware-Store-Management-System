package com.example.demo.billing;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** A printable document, never a posted sales order or accounting entry. */
@Entity @Data @Table(name="billing_document",uniqueConstraints=@UniqueConstraint(columnNames={"operator_id","request_key"}))
public class BillingDocument {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="operator_id") private Long operatorId;
    @Column(name="request_key",length=80) private String requestKey;
    @Version private Long version;
    private String status;
    private String documentNumber;
    @Lob private String contentJson;
    private BigDecimal totalAmount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
