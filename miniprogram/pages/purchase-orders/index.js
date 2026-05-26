const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    purchaseOrders: [],
    loading: true
  },

  onLoad() {
    this.loadPurchaseOrders()
  },

  onShow() {
    this.loadPurchaseOrders()
  },

  loadPurchaseOrders() {
    this.setData({ loading: true })
    api.getPurchaseOrders().then((purchaseOrders) => {
      this.setData({ purchaseOrders: purchaseOrders || [], loading: false })
    }).catch(() => {
      this.setData({ purchaseOrders: [], loading: false })
    })
  },

  openDetail(event) {
    openPage('/pages/purchase-detail/index', { id: event.currentTarget.dataset.id })
  },

  openCreate() {
    openPage('/pages/purchase-form/index')
  },

  openEdit(event) {
    openPage('/pages/purchase-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' })
  }
})
