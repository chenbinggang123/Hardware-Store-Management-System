const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    keyword: '',
    warningOnly: false,
    inventories: [],
    loading: true
  },

  onLoad() {
    this.loadInventories()
  },

  onShow() {
    this.loadInventories()
  },

  toggleWarning() {
    this.setData({ warningOnly: !this.data.warningOnly }, () => this.loadInventories())
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value })
  },

  loadInventories() {
    this.setData({ loading: true })
    api.getInventories({ warningOnly: this.data.warningOnly, keyword: this.data.keyword }).then((inventories) => {
      this.setData({ inventories: inventories || [], loading: false })
    }).catch(() => {
      this.setData({ inventories: [], loading: false })
    })
  },

  openDetail(event) {
    openPage('/pages/inventory-detail/index', { id: event.currentTarget.dataset.id })
  },

  openAdjust(event) {
    openPage('/pages/inventory-adjust/index', { id: event.currentTarget.dataset.id })
  }
})
