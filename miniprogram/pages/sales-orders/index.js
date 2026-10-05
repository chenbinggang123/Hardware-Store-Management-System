const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

function buildMetrics(orders) {
  const totalAmount = orders.reduce((sum, item) => sum + Number(item.totalAmount || 0), 0)
  const debtAmount = orders.reduce((sum, item) => sum + Number(item.debtAmount || 0), 0)
  const unpaidCount = orders.filter((item) => Number(item.debtAmount || 0) > 0).length
  return {
    total: orders.length,
    totalAmount: totalAmount.toFixed(2),
    debtAmount: debtAmount.toFixed(2),
    unpaidCount
  }
}

Page({
  data: {
    salesOrders: [],
    loading: true,
    loadError: '',
    metrics: { total: 0, totalAmount: '0.00', debtAmount: '0.00', unpaidCount: 0 }
  },

  onLoad() {
    this.loadSalesOrders()
  },

  onShow() {
    this.loadSalesOrders()
  },

  onPullDownRefresh() {
    this.loadSalesOrders(true)
  },

  loadSalesOrders(stopPullDownRefresh) {
    this.setData({ loading: true, loadError: '' })
    Promise.all([api.getSalesOrders(), api.getCustomers()]).then(([salesOrders, customers]) => {
      const customerMap = (customers || []).reduce((map, customer) => { map[String(customer.id)] = customer.name; return map }, {})
      const orders = (salesOrders || []).map((item) => ({ ...item, customerName: customerMap[String(item.customerId)] || `客户 ${item.customerId}` }))
      this.setData({
        salesOrders: orders,
        loading: false,
        metrics: buildMetrics(orders)
      })
    }).catch((error) => {
      this.setData({
        salesOrders: [],
        loading: false,
        loadError: (error && (error.message || error.errMsg)) || '销售单加载失败',
        metrics: { total: 0, totalAmount: '0.00', debtAmount: '0.00', unpaidCount: 0 }
      })
    }).finally(() => {
      if (stopPullDownRefresh) wx.stopPullDownRefresh()
    })
  },

  openDetail(event) {
    openPage('/pages/sales-detail/index', { id: event.currentTarget.dataset.id })
  },

  openCreate() {
    openPage('/pages/sales-form/index')
  },

  openAgent() {
    openPage('/pages/agent/index')
  },

  openPurchases() { openPage('/pages/purchase-orders/index') },
  retryLoad() { this.loadSalesOrders() },

  openEdit(event) {
    openPage('/pages/sales-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' })
  }
})
