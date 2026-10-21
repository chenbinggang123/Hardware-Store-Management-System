const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

function currentMonth() {
  const date = new Date()
  return `${date.getFullYear()}-${`${date.getMonth() + 1}`.padStart(2, '0')}`
}

Page({
  data: { month: currentMonth(), summary: null, loading: true },

  onShow() { this.load() },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()) },

  changeMonth(event) {
    this.setData({ month: event.detail.value }, () => this.load())
  },

  load() {
    this.setData({ loading: true })
    return api.getMonthlySummary(this.data.month)
      .then((summary) => this.setData({ summary }))
      .catch(() => showToast('汇总加载失败，请下拉重试'))
      .finally(() => this.setData({ loading: false }))
  },

  openLedger(event) {
    const id = event.currentTarget.dataset.id
    if (id) openPage('/pages/factory-ledger-detail/index', { id, month: this.data.month })
  },

  openRecords() { openPage('/pages/billing-list/index') },
  openSettlements() { openPage('/pages/factory-settlements/index', { month: this.data.month }) },
  openOldReports() { openPage('/pages/report-center/index') }
})
