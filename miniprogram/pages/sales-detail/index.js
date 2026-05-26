const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    order: null,
    customers: [],
    customerName: '',
    paymentAmount: '',
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
    api.stockOutSalesOrder(this.data.id).then(() => {
      showToast('销售单已出库', 'success')
      this.loadDetail()
    })
  },

  registerPayment() {
    api.registerSalesPayment(this.data.id, {
      receivedAmount: Number(this.data.paymentAmount || 0),
      paymentMethod: '现金',
      remark: '小程序界面登记'
    }).then(() => {
      showToast('收款已登记', 'success')
      this.setData({ paymentAmount: '' })
      this.loadDetail()
    })
  }
})
