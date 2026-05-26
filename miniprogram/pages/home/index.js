const { openPage } = require('../../utils/router')
const api = require('../../utils/api')

const modules = [
  { title: '商品管理', icon: '📦', iconClass: 'product', desc: '商品、价格、上下架、图片管理', path: '/pages/products/index' },
  { title: '供应商管理', icon: '🏭', iconClass: 'supplier', desc: '供应商资料、采购历史、价格趋势', path: '/pages/suppliers/index' },
  { title: '客户管理', icon: '👤', iconClass: 'customer', desc: '客户资料、订单、欠款记录', path: '/pages/customers/index' },
  { title: '进货管理', icon: '📥', iconClass: 'purchase', desc: '采购单、入库、库位分配', path: '/pages/purchase-orders/index' },
  { title: '销售管理', icon: '📤', iconClass: 'sales', desc: '销售单、出库、收款登记', path: '/pages/sales-orders/index' },
  { title: '库存管理', icon: '📋', iconClass: 'stock', desc: '实时库存、盘点、预警', path: '/pages/inventories/index' }
]

Page({
  data: {
    modules,
    currentUser: {},
    stats: { productCount: 0, purchaseCount: 0, salesCount: 0, warningCount: 0 },
    report: { sales: 0, purchase: 0 },
    recentLogs: []
  },

  onShow() {
    const app = getApp()
    this.setData({
      currentUser: app.globalData.currentUser || { name: '老板', role: '管理员' }
    })
    this.loadDashboard()
  },

  onPullDownRefresh() {
    this.loadDashboard(true)
  },

  loadDashboard(stopPullDownRefresh) {
    Promise.all([
      api.getProducts(),
      api.getPurchaseOrders(),
      api.getSalesOrders(),
      api.getDailyReport(),
      api.getLogs()
    ]).then(([products, purchases, sales, report, logs]) => {
      const warnings = (products || []).filter(p => (p.stock || 0) <= 10).length
      this.setData({
        stats: {
          productCount: (products || []).length,
          purchaseCount: (purchases || []).length,
          salesCount: (sales || []).length,
          warningCount: warnings
        },
        report: report || { sales: 0, purchase: 0 },
        recentLogs: (logs || []).slice(0, 5)
      })
    }).catch(() => {}).finally(() => {
      if (stopPullDownRefresh) wx.stopPullDownRefresh()
    })
  },

  openModule(event) {
    const { path } = event.currentTarget.dataset
    openPage(path)
  }
})
