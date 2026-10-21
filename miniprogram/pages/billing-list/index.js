const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

const PAGE_SIZE = 20

function money(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : '0.00'
}

function rowView(item) {
  const confirmed = item.status === 'CONFIRMED'
  const voided = item.status === 'VOIDED'
  return {
    ...item,
    title: item.customerName || '未填客户',
    totalText: money(item.total),
    statusText: voided ? '已作废' : (confirmed ? (item.ledgerName ? `已记入 ${item.ledgerName}` : '已保存 · 未记账') : '草稿'),
    statusTone: voided ? 'tag-neutral' : (confirmed ? (item.ledgerName ? 'tag-success' : 'tag-warning') : 'tag-neutral'),
    meta: [item.number || '草稿', item.date, `${item.itemCount || 0} 项`].filter(Boolean).join(' · ')
  }
}

Page({
  data: {
    keyword: '',
    items: [],
    page: 0,
    totalPages: 0,
    total: 0,
    loading: true,
    loadingMore: false
  },

  onShow() {
    this.load(true)
  },

  onPullDownRefresh() {
    this.load(true).finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.loadingMore || this.data.page + 1 >= this.data.totalPages) return
    this.load(false)
  },

  changeKeyword(event) {
    this.setData({ keyword: event.detail.value || '' })
    clearTimeout(this.searchTimer)
    this.searchTimer = setTimeout(() => this.load(true), 300)
  },

  clearKeyword() {
    this.setData({ keyword: '' }, () => this.load(true))
  },

  load(reset) {
    const page = reset ? 0 : this.data.page + 1
    this.setData(reset ? { loading: true } : { loadingMore: true })
    return api.getBillingRecords({ keyword: this.data.keyword.trim(), page, size: PAGE_SIZE }).then((result) => {
      const rows = ((result && result.items) || []).map(rowView)
      this.setData({
        items: reset ? rows : this.data.items.concat(rows),
        page: (result && result.page) || 0,
        totalPages: (result && result.totalPages) || 0,
        total: (result && result.total) || 0
      })
    }).catch(() => showToast('开单记录加载失败，请下拉重试'))
      .finally(() => this.setData({ loading: false, loadingMore: false }))
  },

  openDocument(event) {
    const id = event.currentTarget.dataset.id
    if (id) openPage('/pages/billing-editor/index', { id })
  },

  openCreate() {
    openPage('/pages/billing-editor/index')
  },

  onUnload() {
    clearTimeout(this.searchTimer)
  }
})
