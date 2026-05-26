const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    salesOrders: [],
    loading: true
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
    this.setData({ loading: true })
    api.getSalesOrders().then((salesOrders) => {
      this.setData({
        salesOrders: salesOrders || [],
        loading: false
      })
    }).catch(() => {
      this.setData({
        salesOrders: [],
        loading: false
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

  openEdit(event) {
    openPage('/pages/sales-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' })
  }
})
