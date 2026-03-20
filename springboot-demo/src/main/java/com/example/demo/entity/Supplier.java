package com.example.demo.entity;

import lombok.Data;
import lombok.Setter;
import javax.persistence.*;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 供应商实体类
 */
@Entity
@Table(name = "supplier")
@Data
@Setter
public class Supplier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // 供应商名称
    private String contact; // 联系人
    private String phone; // 联系电话
    private String address; // 地址
    private String remark; // 备注
    private LocalDateTime createTime; // 创建时间
}
