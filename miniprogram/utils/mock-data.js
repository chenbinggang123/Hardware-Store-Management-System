module.exports = {
  products: [
    {
      id: 1,
      name: '羊角锤',
      barcode: '690000000001',
      spec: '16oz',
      unit: '把',
      unitConvert: '1把',
      retailPrice: 28,
      wholesalePrice: 24.5,
      oldCustomerPrice: 23,
      costPrice: 18.8,
      stock: 48,
      locationId: 'A-01',
      sourceFactory: '宁波五金厂',
      imageUrl: '',
      status: 1,
      createTime: '2026-03-20T09:30:00'
    },
    {
      id: 2,
      name: '冲击电钻',
      barcode: '690000000002',
      spec: '680W',
      unit: '台',
      unitConvert: '1台',
      retailPrice: 268,
      wholesalePrice: 245,
      oldCustomerPrice: 238,
      costPrice: 198,
      stock: 12,
      locationId: 'B-03',
      sourceFactory: '江苏机电设备厂',
      imageUrl: '',
      status: 1,
      createTime: '2026-03-18T10:20:00'
    },
    {
      id: 3,
      name: 'PVC水管',
      barcode: '690000000003',
      spec: '20mm',
      unit: '根',
      unitConvert: '1根',
      retailPrice: 16,
      wholesalePrice: 13.5,
      oldCustomerPrice: 12.8,
      costPrice: 9.6,
      stock: 96,
      locationId: 'C-02',
      sourceFactory: '佛山建材厂',
      imageUrl: '',
      status: 0,
      createTime: '2026-03-15T14:00:00'
    }
  ],
  suppliers: [
    {
      id: 1,
      name: '宁波五金厂',
      contact: '王经理',
      phone: '13800000001',
      address: '浙江宁波',
      remark: '锤子和手工具供应商',
      createTime: '2026-03-08T09:00:00'
    },
    {
      id: 2,
      name: '江苏机电设备厂',
      contact: '刘经理',
      phone: '13800000002',
      address: '江苏常州',
      remark: '电动工具供应商',
      createTime: '2026-03-09T10:00:00'
    }
  ],
  customers: [
    {
      id: 1,
      name: '李师傅',
      type: '老客户',
      phone: '13900000001',
      address: '城北建材市场',
      debt: 168,
      remark: '常购五金耗材',
      createTime: '2026-03-10T11:00:00'
    },
    {
      id: 2,
      name: '宏达装修队',
      type: 'B',
      phone: '13900000002',
      address: '开发区工地',
      debt: 300,
      remark: '批量采购客户',
      createTime: '2026-03-11T11:30:00'
    },
    {
      id: 3,
      name: '张阿姨',
      type: 'C',
      phone: '13900000003',
      address: '幸福小区',
      debt: 0,
      remark: '零售客户',
      createTime: '2026-03-16T15:20:00'
    }
  ],
  purchaseOrders: [
    {
      id: 1,
      orderNumber: 'PO202603230001',
      supplierId: 1,
      operatorId: 1,
      orderTime: '2026-03-23T10:30:00',
      totalAmount: 564,
      status: '待入库',
      remark: '补充手工具',
      createTime: '2026-03-23T10:30:00',
      items: [
        {
          productId: 1,
          productName: '羊角锤',
          quantity: 30,
          price: 18.8,
          amount: 564,
          locationId: 'A-01'
        }
      ]
    },
    {
      id: 2,
      orderNumber: 'PO202603240001',
      supplierId: 2,
      operatorId: 1,
      orderTime: '2026-03-24T09:10:00',
      totalAmount: 396,
      status: '已入库',
      remark: '电钻补货',
      createTime: '2026-03-24T09:10:00',
      items: [
        {
          productId: 2,
          productName: '冲击电钻',
          quantity: 2,
          price: 198,
          amount: 396,
          locationId: 'B-03'
        }
      ]
    }
  ],
  salesOrders: [
    {
      id: 1,
      orderNumber: 'SO202603250001',
      customerId: 1,
      operatorId: 1,
      orderTime: '2026-03-25T14:20:00',
      totalAmount: 168,
      status: '已出库',
      payStatus: '未付',
      receivedAmount: 0,
      debtAmount: 168,
      createTime: '2026-03-25T14:20:00',
      items: [
        {
          productId: 1,
          productName: '羊角锤',
          quantity: 6,
          price: 28,
          amount: 168
        }
      ]
    },
    {
      id: 2,
      orderNumber: 'SO202603260001',
      customerId: 2,
      operatorId: 1,
      orderTime: '2026-03-26T16:10:00',
      totalAmount: 490,
      status: '待出库',
      payStatus: '部分',
      receivedAmount: 190,
      debtAmount: 300,
      createTime: '2026-03-26T16:10:00',
      items: [
        {
          productId: 2,
          productName: '冲击电钻',
          quantity: 2,
          price: 245,
          amount: 490
        }
      ]
    }
  ],
  inventories: [
    {
      id: 1,
      productId: 1,
      quantity: 48,
      locationId: 'A-01',
      warningThreshold: 12,
      lastUpdateTime: '2026-03-26T08:00:00'
    },
    {
      id: 2,
      productId: 2,
      quantity: 12,
      locationId: 'B-03',
      warningThreshold: 6,
      lastUpdateTime: '2026-03-26T08:30:00'
    },
    {
      id: 3,
      productId: 3,
      quantity: 96,
      locationId: 'C-02',
      warningThreshold: 20,
      lastUpdateTime: '2026-03-26T09:00:00'
    }
  ],
  inventoryLogs: [
    {
      id: 1,
      productId: 2,
      productName: '冲击电钻',
      changeType: '入库',
      quantity: 2,
      beforeQuantity: 10,
      afterQuantity: 12,
      operatorId: 1,
      relatedOrderId: 2,
      remark: '采购单入库：PO202603240001',
      createTime: '2026-03-24T09:20:00'
    },
    {
      id: 2,
      productId: 1,
      productName: '羊角锤',
      changeType: '出库',
      quantity: 6,
      beforeQuantity: 54,
      afterQuantity: 48,
      operatorId: 1,
      relatedOrderId: 1,
      remark: '销售单出库：SO202603250001',
      createTime: '2026-03-25T14:40:00'
    }
  ],
  users: [
    {
      id: 1,
      username: 'admin',
      password: 'admin123',
      name: '系统管理员',
      phone: '13800000000',
      role: '老板',
      status: 1,
      createTime: '2026-03-01T08:00:00'
    },
    {
      id: 2,
      username: 'clerk',
      password: 'clerk123',
      name: '门店店员',
      phone: '13800000009',
      role: '店员',
      status: 1,
      createTime: '2026-03-03T09:10:00'
    }
  ],
  logs: [
    {
      id: 1,
      operatorId: 1,
      module: 'PRODUCT',
      action: 'UPDATE_STATUS',
      detail: '将 PVC水管 设置为下架',
      createTime: '2026-03-26T10:00:00'
    },
    {
      id: 2,
      operatorId: 1,
      module: 'PURCHASE',
      action: 'STOCK_IN',
      detail: '采购单入库：PO202603240001',
      createTime: '2026-03-24T09:20:00'
    },
    {
      id: 3,
      operatorId: 1,
      module: 'SALES',
      action: 'PAYMENT',
      detail: '销售单收款：SO202603260001',
      createTime: '2026-03-26T16:20:00'
    }
  ]
}
