const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

function buildMetrics(inventories) {
  return {
    total: inventories.length,
    warning: inventories.filter((item) => Number(item.quantity || 0) <= Number(item.warningThreshold || 0)).length,
    quantity: inventories.reduce((sum, item) => sum + Number(item.quantity || 0), 0)
  }
}

Page({
  data: {
    keyword: '',
    warningOnly: false,
    inventories: [],
    loading: true,
    loadError: '',
    metrics: { total: 0, warning: 0, quantity: 0 }
  },

  onLoad() {
    this.loadInventories()
  },

  onShow() {
    this.loadInventories()
  },

  onPullDownRefresh() {
    this.loadInventories(true)
  },

  toggleWarning() {
    this.setData({ warningOnly: !this.data.warningOnly }, () => this.loadInventories())
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value })
  },

  loadInventories(stopPullDownRefresh) {
    this.setData({ loading: true, loadError: '' })
    Promise.all([api.getInventories({ warningOnly: this.data.warningOnly }), api.getProducts()]).then(([inventories, products]) => {
      const productMap = (products || []).reduce((map, product) => {
        map[String(product.id)] = product
        return map
      }, {})
      const keyword = String(this.data.keyword || '').trim().toLowerCase()
      const items = (inventories || []).map((item) => {
        const product = productMap[String(item.productId)] || {}
        return { ...item, productName: product.name || `商品 ${item.productId}`, productSpec: product.spec || product.barcode || '未填写规格' }
      }).filter((item) => !keyword || `${item.productName} ${item.productSpec} ${item.productId}`.toLowerCase().includes(keyword))
      this.setData({ inventories: items, loading: false, metrics: buildMetrics(items) })
    }).catch((error) => {
      this.setData({ inventories: [], loading: false, loadError: (error && (error.message || error.errMsg)) || '库存数据加载失败', metrics: { total: 0, warning: 0, quantity: 0 } })
    }).finally(() => {
      if (stopPullDownRefresh) wx.stopPullDownRefresh()
    })
  },

  openDetail(event) {
    openPage('/pages/inventory-detail/index', { id: event.currentTarget.dataset.id })
  },

  openAdjust(event) {
    openPage('/pages/inventory-adjust/index', { id: event.currentTarget.dataset.id })
  },

  openAgent() {
    openPage('/pages/agent/index')
  },

  retryLoad() {
    this.loadInventories()
  }
})
