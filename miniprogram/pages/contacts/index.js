const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: { type: 'supplier', keyword: '', suppliers: [], customers: [], visible: [], loading: true, loadError: '' },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData(true) },
  loadData(stopRefresh) {
    this.setData({ loading: true, loadError: '' })
    Promise.all([api.getSuppliers(), api.getCustomers()]).then(([suppliers, customers]) => {
      this.setData({ suppliers: suppliers || [], customers: customers || [], loading: false }, () => this.updateVisible())
    }).catch((error) => this.setData({ loading: false, visible: [], loadError: (error && (error.message || error.errMsg)) || '往来单位加载失败' })).finally(() => { if (stopRefresh) wx.stopPullDownRefresh() })
  },
  updateVisible() {
    const source = this.data.type === 'supplier' ? this.data.suppliers : this.data.customers
    const keyword = String(this.data.keyword || '').trim().toLowerCase()
    this.setData({ visible: source.filter((item) => !keyword || `${item.name || ''} ${item.contact || ''} ${item.phone || ''}`.toLowerCase().includes(keyword)) })
  },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }, () => this.updateVisible()) },
  selectType(event) {
    const type = event.currentTarget.dataset.type
    this.setData({ type }, () => this.updateVisible())
  },
  openDetail(event) {
    const path = this.data.type === 'supplier' ? '/pages/supplier-detail/index' : '/pages/customer-detail/index'
    openPage(path, { id: event.currentTarget.dataset.id })
  },
  openCreate() { openPage(this.data.type === 'supplier' ? '/pages/supplier-form/index' : '/pages/customer-form/index') },
  retryLoad() { this.loadData() }
})
