package com.example.demo.billing;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity @Data
@Table(name="billing_product_usage", uniqueConstraints=@UniqueConstraint(columnNames={"operator_id","candidate_key"}))
public class BillingProductUsage {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="operator_id", nullable=false) private Long operatorId;
    @Column(name="candidate_key", nullable=false, length=255) private String candidateKey;
    @Column(name="use_count", nullable=false) private Long useCount;
    @Column(name="last_used_time", nullable=false) private LocalDateTime lastUsedTime;
}
