
package com.example.demo.entity;

import lombok.Data;
import lombok.Setter;
import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体类
 */
@Entity
@Table(name = "product")
@Data
@Setter
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // 商品名称
    private String barcode; // 条码
    private String spec; // 规格
    private String unit; // 单位
    private String unitConvert; // 单位换算（可用JSON字符串存储）
    private BigDecimal retailPrice; // 零售价
    private BigDecimal wholesalePrice; // 批发价
    private BigDecimal oldCustomerPrice; // 老客户价
    private BigDecimal costPrice; // 进价
    private Integer stock; // 库存数量
    private String locationId; // 库位ID
    private String sourceFactory; // 源头厂商
    private String imageUrl; // 商品图片
    private Integer status; // 状态（1上架/0下架）
    private LocalDateTime createTime; // 创建时间
}
