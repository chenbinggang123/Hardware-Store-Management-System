const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    order: null,
    customers: [],
    customerName: '',
    paymentAmount: '',
    saving: false,
    loading: true,
    loadError: ''
  },

  onLoad(options) {
    this.setData({ id: options.id || '' })
    this.loadDetail()
  },

  onShow() {
    if (this.data.id) {
      this.loadDetail()
    }
  },

  loadDetail() {
    this.setData({
      loading: true,
      loadError: ''
    })
    Promise.all([api.getSalesOrder(this.data.id), api.getCustomers()]).then(([order, customers]) => {
      const customer = customers.find((item) => Number(item.id) === Number(order ? order.customerId : ''))
      this.setData({
        order,
        customers,
        customerName: customer ? customer.name : `客户 ${(order && order.customerId) || '--'}`
      })
    }).catch((error) => {
      this.setData({
        order: null,
        customers: [],
        customerName: '',
        loadError: (error && (error.message || error.errMsg)) || '销售单详情加载失败'
      })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  openEdit() {
    openPage('/pages/sales-form/index', { id: this.data.id, mode: 'edit' })
  },

  updatePayment(event) {
    this.setData({ paymentAmount: event.detail.value })
  },

  stockOut() {
    if (this.data.saving) return
    wx.showModal({ title: '确认销售出库', content: '确认后将扣减这张销售单中的商品库存，不会自动登记收款。', confirmText: '确认出库' }).then((result) => {
      if (!result.confirm) return null
      this.setData({ saving: true })
      return api.stockOutSalesOrder(this.data.id).then(() => { showToast('销售单已出库', 'success'); this.loadDetail() })
        .catch((error) => showToast((error && (error.message || error.errMsg)) || '出库失败'))
        .finally(() => this.setData({ saving: false }))
    }).catch(() => {})
  },

  registerPayment() {
    if (this.data.saving) return
    const amount = Number(this.data.paymentAmount)
    if (!Number.isFinite(amount) || amount <= 0) return showToast('请输入正确的收款金额')
    wx.showModal({ title: '确认登记收款', content: `将为 ${this.data.customerName} 登记现金收款 ¥${amount.toFixed(2)}，不会再次修改商品库存。`, confirmText: '确认收款' }).then((result) => {
      if (!result.confirm) return null
      this.setData({ saving: true })
      return api.registerSalesPayment(this.data.id, { receivedAmount: amount, paymentMethod: '现金', remark: '小程序界面登记' }).then(() => {
        showToast('收款已登记', 'success'); this.setData({ paymentAmount: '' }); this.loadDetail()
      }).catch((error) => showToast((error && (error.message || error.errMsg)) || '收款登记失败'))
        .finally(() => this.setData({ saving: false }))
    }).catch(() => {})
  }
})
