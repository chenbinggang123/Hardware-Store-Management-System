package com.example.demo.entity;

import lombok.Data;
import lombok.Setter;
import javax.persistence.*;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 库存实体类
 */
@Entity
@Table(name = "inventory")
@Data
@Setter
public class Inventory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long productId; // 商品ID
    private Integer quantity; // 库存数量
    private String locationId; // 库位ID
    private LocalDateTime lastUpdateTime; // 最后更新时间
}
