const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    id: '',
    inventory: null,
    product: null,
    logs: [],
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
    Promise.all([api.getInventory(this.data.id), api.getInventoryLogs(this.data.id), api.getProducts()]).then(([inventory, logs, products]) => {
      const product = products.find((item) => Number(item.id) === Number(inventory ? inventory.productId : ''))
      this.setData({ inventory, logs, product })
    }).catch((error) => {
      this.setData({
        inventory: null,
        product: null,
        logs: [],
        loadError: (error && (error.message || error.errMsg)) || '库存详情加载失败'
      })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  openAdjust() {
    openPage('/pages/inventory-adjust/index', { id: this.data.id })
  }
})
