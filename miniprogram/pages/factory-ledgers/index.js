const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    month: ledgerStore.currentMonth(), keyword: '', ledgers: [],
    totals: { factoryCount: 0, totalDueAmount: '0.00' }, loading: true, error: ''
  },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedgers({ month: this.data.month, keyword: this.data.keyword }).then((result) => {
      this.setData({ ledgers: result.ledgers || [], totals: result.totals || this.data.totals, loading: false })
    }).catch((error) => {
      this.setData({ loading: false, error: (error && error.message) || '账本加载失败，请重试' })
    })
  },
  openCreate() { openPage('/pages/factory-ledger-form/index') },
  openMonthlySummary() { openPage('/pages/monthly-summary/index', { month: this.data.month }) },
  openDetail(event) { openPage('/pages/factory-ledger-detail/index', { id: event.currentTarget.dataset.id, month: this.data.month }) }
})
