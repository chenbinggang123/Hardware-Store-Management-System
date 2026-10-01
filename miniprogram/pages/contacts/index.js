const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: { type: 'supplier', suppliers: [], customers: [], visible: [], loading: true },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData(true) },
  loadData(stopRefresh) {
    this.setData({ loading: true })
    Promise.all([api.getSuppliers(), api.getCustomers()]).then(([suppliers, customers]) => {
      this.setData({ suppliers: suppliers || [], customers: customers || [], visible: this.data.type === 'supplier' ? suppliers || [] : customers || [], loading: false })
    }).catch(() => this.setData({ loading: false })).finally(() => { if (stopRefresh) wx.stopPullDownRefresh() })
  },
  selectType(event) {
    const type = event.currentTarget.dataset.type
    this.setData({ type, visible: type === 'supplier' ? this.data.suppliers : this.data.customers })
  },
  openDetail(event) {
    const path = this.data.type === 'supplier' ? '/pages/supplier-detail/index' : '/pages/customer-detail/index'
    openPage(path, { id: event.currentTarget.dataset.id })
  }
})
