package com.example.demo.entity;

import lombok.Data;
import lombok.Setter;
import javax.persistence.*;
import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 客户实体类
 */
@Entity
@Table(name = "customer")
@Data
@Setter
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name; // 客户名称
    private String type; // 客户类型（零售/批发/老客户）
    private String phone; // 联系电话
    private String address; // 地址
    private String remark; // 备注
    private LocalDateTime createTime; // 创建时间
}
