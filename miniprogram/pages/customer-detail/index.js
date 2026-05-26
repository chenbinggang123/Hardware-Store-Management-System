const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    id: '',
    customer: null,
    orders: [],
    accounts: [],
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
    Promise.all([
      api.getCustomer(this.data.id),
      api.getCustomerSalesOrders(this.data.id),
      api.getCustomerAccounts(this.data.id)
    ]).then(([customer, orders, accounts]) => {
      this.setData({ customer, orders, accounts })
    }).catch((error) => {
      this.setData({
        customer: null,
        orders: [],
        accounts: [],
        loadError: (error && (error.message || error.errMsg)) || '客户详情加载失败'
      })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  openEdit() {
    openPage('/pages/customer-form/index', { id: this.data.id, mode: 'edit' })
  }
})
