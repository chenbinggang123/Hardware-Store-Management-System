package com.example.demo.entity;

import com.example.demo.common.JsonColumnSupport;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "factory_ledger_transaction")
@Data
public class FactoryLedgerTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String transactionNo;
    private Long ledgerId;
    private String type;
    private LocalDate transactionDate;
    private String receiptNo;
    private Long amountCents;
    private String remark;
    private String source;
    private Long operatorId;
    private LocalDateTime createTime;
    /** ACTIVE：有效；REVERSED：已被红字冲销。冲销记录本身也是 ACTIVE，金额为负。 */
    private String status = "ACTIVE";
    /** 冲销记录指向被冲销的原记录。 */
    private Long reversalOfId;

    @Lob
    @Column(name = "items_json")
    private String itemsJson;

    @Transient
    private List<FactoryLedgerItem> items = new ArrayList<>();

    @PostLoad
    public void loadItems() {
        this.items = JsonColumnSupport.readList(itemsJson, new TypeReference<List<FactoryLedgerItem>>() { });
    }

    @PrePersist
    @PreUpdate
    public void saveItems() {
        this.itemsJson = JsonColumnSupport.writeList(items);
    }
}
