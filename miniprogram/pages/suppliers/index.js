const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    keyword: '',
    suppliers: [],
    loading: true
  },

  onLoad() {
    this.loadSuppliers()
  },

  onShow() {
    this.loadSuppliers()
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value })
  },

  loadSuppliers() {
    const params = {}
    if (this.data.keyword) params.keyword = this.data.keyword
    this.setData({ loading: true })
    api.getSuppliers(params).then((suppliers) => {
      this.setData({ suppliers: suppliers || [], loading: false })
    }).catch(() => {
      this.setData({ suppliers: [], loading: false })
    })
  },

  openDetail(event) {
    openPage('/pages/supplier-detail/index', { id: event.currentTarget.dataset.id })
  },

  openCreate() {
    openPage('/pages/supplier-form/index')
  },

  openEdit(event) {
    openPage('/pages/supplier-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' })
  }
})
