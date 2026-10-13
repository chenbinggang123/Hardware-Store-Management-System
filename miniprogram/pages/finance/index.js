const api = require('../../utils/api')
const ledgerStore = require('../../utils/factory-ledger')
const { openPage } = require('../../utils/router')

function amount(value) {
  const number = Number(value || 0)
  return Number.isFinite(number) ? number : 0
}

function money(value) { return amount(value).toFixed(2) }

function dateText(value) {
  const text = String(value || '').replace('T', ' ')
  return text.length >= 10 ? text.slice(5, 10).replace('-', '月') + '日' : '暂无记录'
}

function customerRow(item, orders, month) {
  const periodOrders = orders.filter((order) => `${order.customerId}` === `${item.id}` && String(order.orderTime || order.createTime || '').startsWith(month))
  const periodAmount = periodOrders.reduce((sum, order) => sum + amount(order.totalAmount), 0)
  const periodPaid = periodOrders.reduce((sum, order) => sum + amount(order.receivedAmount), 0)
  const latestOrder = orders.filter((order) => `${order.customerId}` === `${item.id}`).sort((left, right) => String(right.orderTime || right.createTime || '').localeCompare(String(left.orderTime || left.createTime || '')))[0]
  const balance = amount(item.debt)
  return { key: `customer-${item.id}`, id: item.id, type: 'customer', typeText: '客户', mark: '客', name: item.name || `客户 ${item.id}`, contact: item.phone || item.contact || '未填写联系方式', periodAmount, periodPaid, balance, balanceText: money(balance), periodAmountText: money(periodAmount), periodPaidText: money(periodPaid), recentText: dateText(latestOrder && (latestOrder.orderTime || latestOrder.createTime)), recentValue: String(latestOrder && (latestOrder.orderTime || latestOrder.createTime) || ''), statusText: balance > 0 ? '待收' : '已结清' }
}

function factoryRow(item) {
  const balance = amount(item.balanceAmount !== undefined ? item.balanceAmount : amount(item.balanceCents) / 100)
  const periodAmount = amount(item.purchaseAmount !== undefined ? item.purchaseAmount : amount(item.purchaseCents) / 100)
  const periodPaid = amount(item.paymentAmount !== undefined ? item.paymentAmount : amount(item.paymentCents) / 100)
  const recentValue = String(item.updatedAt || item.updateTime || item.startDate || '')
  return { key: `factory-${item.id}`, id: item.id, type: 'factory', typeText: '厂商', mark: '厂', name: item.factoryName || `厂商 ${item.id}`, contact: item.contactPerson || item.phone || item.ledgerNo || '未填写联系方式', periodAmount, periodPaid, balance, balanceText: money(balance), periodAmountText: money(periodAmount), periodPaidText: money(periodPaid), recentText: dateText(recentValue), recentValue, statusText: balance > 0 ? '待收' : '已结清' }
}

Page({
  data: { type: 'all', keyword: '', sort: 'balance', sortIndex: 0, sortOptions: ['欠款最高', '最近发生', '名称'], month: ledgerStore.currentMonth(), rows: [], visible: [], summary: { dueText: '0.00', paidText: '0.00', pendingText: '0.00', pendingCount: 0 }, loading: true, loadError: '' },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData(true) },
  loadData(stopRefresh) {
    this.setData({ loading: true, loadError: '' })
    Promise.all([api.getCustomers(), api.getSalesOrders(), api.getFactoryLedgers({ month: this.data.month })]).then(([customers, orders, factoryResult]) => {
      const customerRows = (customers || []).map((item) => customerRow(item, orders || [], this.data.month))
      const factoryRows = ((factoryResult && factoryResult.ledgers) || []).map(factoryRow)
      const rows = customerRows.concat(factoryRows)
      const due = rows.reduce((sum, item) => sum + item.periodAmount, 0)
      const paid = rows.reduce((sum, item) => sum + item.periodPaid, 0)
      const pending = rows.reduce((sum, item) => sum + item.balance, 0)
      this.setData({ rows, summary: { dueText: money(due), paidText: money(paid), pendingText: money(pending), pendingCount: rows.filter((item) => item.balance > 0).length } }, () => this.updateVisible())
    }).catch((error) => this.setData({ rows: [], visible: [], loadError: (error && (error.message || error.errMsg)) || '财务数据加载失败' })).finally(() => {
      this.setData({ loading: false })
      if (stopRefresh) wx.stopPullDownRefresh()
    })
  },
  updateVisible() {
    const keyword = String(this.data.keyword || '').trim().toLowerCase()
    const visible = this.data.rows.filter((item) => (this.data.type === 'all' || item.type === this.data.type) && (!keyword || `${item.name} ${item.contact}`.toLowerCase().includes(keyword)))
    visible.sort((left, right) => this.data.sort === 'recent' ? right.recentValue.localeCompare(left.recentValue) : this.data.sort === 'name' ? left.name.localeCompare(right.name, 'zh-CN') : right.balance - left.balance)
    this.setData({ visible })
  },
  selectType(event) { this.setData({ type: event.currentTarget.dataset.type }, () => this.updateVisible()) },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }, () => this.updateVisible()) },
  onSortChange(event) { const sortIndex = Number(event.detail.value || 0); this.setData({ sortIndex, sort: ['balance', 'recent', 'name'][sortIndex] }, () => this.updateVisible()) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  retryLoad() { this.loadData() },
  openRow(event) {
    const row = this.data.rows.find((item) => item.key === event.currentTarget.dataset.key)
    if (!row) return
    if (row.type === 'factory') return openPage('/pages/factory-ledger-detail/index', { id: row.id, month: this.data.month })
    openPage('/pages/customer-detail/index', { id: row.id })
  },
  openAgent() { openPage('/pages/agent/index') }
})
