const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: { id: '', month: ledgerStore.currentMonth(), ledger: null, loading: true, error: '' },
  onLoad(options) { this.setData({ id: options.id || '', month: options.month || ledgerStore.currentMonth() }) },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedger(this.data.id, this.data.month).then((ledger) => {
      if (!ledger) throw new Error('账本不存在')
      this.setData({ ledger, loading: false })
    }).catch((error) => {
      this.setData({ loading: false, error: (error && error.message) || '账本加载失败，请重试' })
    })
  },
  addPurchase() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'PURCHASE' }) },
  addPayment() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'PAYMENT' }) },
  addReturn() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'RETURN' }) }
})
