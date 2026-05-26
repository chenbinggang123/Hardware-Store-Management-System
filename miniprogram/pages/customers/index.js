const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    keyword: '',
    type: '',
    customers: [],
    loading: true
  },

  onLoad() {
    this.loadCustomers()
  },

  onShow() {
    this.loadCustomers()
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value })
  },

  selectType(event) {
    this.setData({ type: event.currentTarget.dataset.type }, () => this.loadCustomers())
  },

  loadCustomers() {
    const params = {}
    if (this.data.keyword) params.keyword = this.data.keyword
    if (this.data.type) params.type = this.data.type
    this.setData({ loading: true })
    api.getCustomers(params).then((customers) => {
      this.setData({ customers: customers || [], loading: false })
    }).catch(() => {
      this.setData({ customers: [], loading: false })
    })
  },

  openDetail(event) {
    openPage('/pages/customer-detail/index', { id: event.currentTarget.dataset.id })
  },

  openCreate() {
    openPage('/pages/customer-form/index')
  },

  openEdit(event) {
    openPage('/pages/customer-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' })
  }
})
