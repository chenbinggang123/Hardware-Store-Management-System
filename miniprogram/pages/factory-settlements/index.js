const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: { month: ledgerStore.currentMonth(), ledgers: [], totals: {}, statusNames: ledgerStore.SETTLEMENT_STATUS.map((item) => item.label), loading: true, error: '' },
  onLoad(options) { if (options.month) this.setData({ month: options.month }) },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedgers({ month: this.data.month }).then((result) => {
      const ledgers = (result.ledgers || []).map((item) => ({ ...item, statusIndex: Math.max(0, ledgerStore.SETTLEMENT_STATUS.findIndex((status) => status.value === item.status)) }))
      this.setData({ ledgers, totals: result.totals || {}, loading: false })
    }).catch((error) => {
      this.setData({ loading: false, error: (error && error.message) || '结账汇总加载失败，请重试' })
    })
  },
  changeStatus(event) {
    const ledgerId = event.currentTarget.dataset.id
    const index = Number(event.detail.value)
    api.setFactoryLedgerSettlementStatus(ledgerId, this.data.month, ledgerStore.SETTLEMENT_STATUS[index].value).then(() => {
      wx.showToast({ title: '进度已更新', icon: 'success' })
      return this.loadData()
    }).catch((error) => wx.showToast({ title: (error && error.message) || '更新失败，请重试', icon: 'none' }))
  },
  openDetail(event) { openPage('/pages/factory-ledger-detail/index', { id: event.currentTarget.dataset.id, month: this.data.month }) }
})
