const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    id: '',
    supplier: null,
    purchaseOrders: [],
    trends: [],
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
      api.getSupplier(this.data.id),
      api.getSupplierPurchaseOrders(this.data.id),
      api.getSupplierPriceTrends(this.data.id)
    ]).then(([supplier, purchaseOrders, trends]) => {
      this.setData({ supplier, purchaseOrders, trends })
    }).catch((error) => {
      this.setData({
        supplier: null,
        purchaseOrders: [],
        trends: [],
        loadError: (error && (error.message || error.errMsg)) || '供应商详情加载失败'
      })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  openEdit() {
    openPage('/pages/supplier-form/index', { id: this.data.id, mode: 'edit' })
  }
})
