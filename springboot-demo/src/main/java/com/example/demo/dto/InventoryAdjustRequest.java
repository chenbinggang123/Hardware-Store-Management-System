package com.example.demo.dto;

import lombok.Data;

/**
 * 库存盘点调整请求
 */
@Data
public class InventoryAdjustRequest {
    private Integer actualQuantity;
    private String reason;
    private Long operatorId;
}
