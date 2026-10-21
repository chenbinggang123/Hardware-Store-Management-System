const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

// 结账只留三步：未对账 → 已对账 → 已结清。旧数据里的其他状态归到最接近的一步显示。
const SIMPLE_STATUS = [
  { value: 'PENDING_SUMMARY', label: '未对账' },
  { value: 'CONFIRMED', label: '已对账' },
  { value: 'CLOSED', label: '已结清' }
]
const STATUS_STEP = { PENDING_SUMMARY: 0, PENDING_CHECK: 0, CONFIRMED: 1, INVOICED: 1, PARTIAL_PAID: 1, PAID: 2, CLOSED: 2 }

Page({
  data: { month: ledgerStore.currentMonth(), ledgers: [], totals: {}, statusNames: SIMPLE_STATUS.map((item) => item.label), loading: true, error: '' },
  onLoad(options) { if (options.month) this.setData({ month: options.month }) },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedgers({ month: this.data.month }).then((result) => {
      const ledgers = (result.ledgers || []).map((item) => {
        const statusIndex = STATUS_STEP[item.status] || 0
        return { ...item, statusIndex, statusLabel: SIMPLE_STATUS[statusIndex].label }
      })
      this.setData({ ledgers, totals: result.totals || {}, loading: false })
    }).catch((error) => {
      this.setData({ loading: false, error: (error && error.message) || '结账汇总加载失败，请重试' })
    })
  },
  changeStatus(event) {
    const ledgerId = event.currentTarget.dataset.id
    const index = Number(event.detail.value)
    api.setFactoryLedgerSettlementStatus(ledgerId, this.data.month, SIMPLE_STATUS[index].value).then(() => {
      wx.showToast({ title: '进度已更新', icon: 'success' })
      return this.loadData()
    }).catch((error) => wx.showToast({ title: (error && error.message) || '更新失败，请重试', icon: 'none' }))
  },
  openDetail(event) { openPage('/pages/factory-ledger-detail/index', { id: event.currentTarget.dataset.id, month: this.data.month }) }
})
