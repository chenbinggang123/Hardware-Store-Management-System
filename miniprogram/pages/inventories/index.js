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
    this.setData({ loading: true })
    api.getInventories({ warningOnly: this.data.warningOnly, keyword: this.data.keyword }).then((inventories) => {
      const items = inventories || []
      this.setData({ inventories: items, loading: false, metrics: buildMetrics(items) })
    }).catch(() => {
      this.setData({ inventories: [], loading: false, metrics: { total: 0, warning: 0, quantity: 0 } })
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
  }
})
