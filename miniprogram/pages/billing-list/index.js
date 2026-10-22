const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

const localDraft = require('../../utils/billing-local-draft')

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
    statusText: item.ledgerManaged ? (item.ledgerName ? `已记入 ${item.ledgerName}` : '已归账本管理') : (voided ? '已作废' : (confirmed ? '已保存 · 未记账' : '草稿')),
    statusTone: voided ? 'tag-neutral' : (confirmed ? (item.ledgerName ? 'tag-success' : 'tag-warning') : 'tag-neutral'),
    meta: [item.documentType === 'PROCUREMENT' ? '采购单' : (item.documentType === 'RETURN' ? '退货单' : '拿货单'), item.number || '草稿', item.date, `${item.itemCount || 0} 项`].filter(Boolean).join(' · ')
  }
}

Page({
  data: {
    keyword: '', status: '', managing: false, selectedIds: [], bulkDeleting: false, loadError: '',
    filters: [{ value: '', label: '全部' }, { value: 'DRAFT', label: '草稿' }, { value: 'CONFIRMED', label: '已保存' }, { value: 'VOIDED', label: '已作废' }],
    filterNames: ['全部', '草稿', '已保存', '已作废'], filterIndex: 0,
    items: [],
    page: 0,
    totalPages: 0,
    total: 0,
    loading: true,
    loadingMore: false
  },

  onLoad(options) {
    this.setData({ selecting: options && options.select === '1', documentType: options && options.documentType || '', factory: options && options.factory || '' })
  },

  onShow() {
    this.load(true)
  },

  onPullDownRefresh() {
    this.load(true).finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.loading || this.data.loadingMore || this.data.bulkDeleting || this.data.page + 1 >= this.data.totalPages) return
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

  // 下拉选择状态（按序号）
  pickStatus(event) {
    const item = this.data.filters[Number(event.detail.value)]
    this.changeStatus({ currentTarget: { dataset: { status: item ? item.value : '' } } })
  },

  changeStatus(event) {
    if (this.data.bulkDeleting) return
    clearTimeout(this.searchTimer)
    const status = event.currentTarget.dataset.status || ''
    this.setData({ status, filterIndex: Math.max(0, this.data.filters.findIndex((item) => item.value === status)), managing: false, selectedIds: [] })
    this.load(true)
  },

  toggleManage() {
    if (this.data.bulkDeleting || this.data.deletingId) return
    const managing = !this.data.managing
    clearTimeout(this.searchTimer)
    const status = managing ? 'DRAFT' : this.data.status
    this.setData({ managing, status, filterIndex: Math.max(0, this.data.filters.findIndex((item) => item.value === status)), selectedIds: [] })
    this.load(true)
  },

  toggleSelection(event) {
    if (this.data.bulkDeleting) return
    const id = Number(event.currentTarget.dataset.id)
    const item = this.data.items.find((row) => Number(row.id) === id)
    if (!item || item.status !== 'DRAFT') return
    let selectedIds = this.data.selectedIds.slice()
    if (selectedIds.includes(id)) selectedIds = selectedIds.filter((value) => value !== id)
    else {
      if (selectedIds.length >= 100) return showToast('每次最多选择 100 张草稿')
      selectedIds.push(id)
    }
    this.setData({ selectedIds, items: this.data.items.map((row) => ({ ...row, selected: selectedIds.includes(Number(row.id)) })) })
  },

  selectLoadedDrafts() {
    if (this.data.bulkDeleting) return
    const loaded = this.data.items.filter((item) => item.status === 'DRAFT')
    const all = this.data.selectedIds.length > 0
    const selectedIds = all ? [] : loaded.slice(0, 100).map((item) => Number(item.id))
    this.setData({ selectedIds, items: this.data.items.map((item) => ({ ...item, selected: selectedIds.includes(Number(item.id)) })) })
    if (!all && loaded.length > 100) showToast('已选择前 100 张草稿')
  },

  deleteSelectedDrafts() {
    if (!this.data.selectedIds.length || this.data.bulkDeleting || this.data.deletingId) return
    const ids = this.data.selectedIds.slice()
    wx.showModal({
      title: '删除选中的 ' + ids.length + ' 张草稿？',
      content: '删除后无法恢复，只清理本次选中的草稿。已保存和已作废单据不受影响。',
      confirmText: '删除草稿', confirmColor: '#B33A32',
      success: (choice) => {
        if (!choice.confirm || this.data.bulkDeleting) return
        this.setData({ bulkDeleting: true })
        api.deleteBillingDrafts(ids).then((result) => {
          ids.forEach((id) => localDraft.remove(localDraft.key(id)))
          showToast('已删除 ' + result.deleted + ' 张草稿', 'success')
          return this.load(true)
        }).catch((error) => showToast(error.message || '删除失败，请刷新后重试'))
          .finally(() => this.setData({ bulkDeleting: false }))
      }
    })
  },

  retryLoad() { this.load(true) },

  load(reset) {
    const requestId = (this.loadRequestId || 0) + 1
    this.loadRequestId = requestId
    const page = reset ? 0 : this.data.page + 1
    this.setData(reset ? { loading: true, loadingMore: false, loadError: '', selectedIds: [] } : { loadingMore: true, loadError: '' })
    return api.getBillingRecords({ keyword: this.data.keyword.trim(), status: this.data.status, documentType: this.data.documentType || '', factory: this.data.factory || '', page, size: PAGE_SIZE }).then((result) => {
      if (requestId !== this.loadRequestId) return
      const rows = ((result && result.items) || []).map((item) => ({ ...rowView(item), selected: this.data.selectedIds.includes(Number(item.id)) }))
      this.setData({
        items: reset ? rows : this.data.items.concat(rows),
        page: (result && result.page) || 0,
        totalPages: (result && result.totalPages) || 0,
        total: (result && result.total) || 0
      })
    }).catch(() => {
      if (requestId === this.loadRequestId) this.setData({ loadError: '开单记录加载失败，请重试' })
    }).finally(() => {
      if (requestId === this.loadRequestId) this.setData({ loading: false, loadingMore: false })
    })
  },

  selectDocument(event) {
    const id = event.currentTarget.dataset.id
    const pages = getCurrentPages()
    const agent = pages.slice(0, -1).reverse().find((page) => typeof page.addBillingReference === 'function')
    if (agent) {
      agent.addBillingReference(id)
      wx.navigateBack({ delta: pages.length - 1 - pages.indexOf(agent) })
    } else openPage('/pages/agent/index', { billingId: id })
  },

  deleteDocument(event) {
    if (this.data.deletingId || this.data.bulkDeleting) return
    const id = event.currentTarget.dataset.id
    const item = this.data.items.find((row) => String(row.id) === String(id))
    if (!item) return
    if (item.ledgerManaged || item.ledgerName) return showToast('已进入工厂账本，请到账本中删除')
    wx.showModal({
      title: item.status === 'DRAFT' ? '删除这张草稿？' : '删除这张单据？',
      content: '删除后无法恢复。已导出的文件需另行删除。',
      confirmText: '删除', confirmColor: '#B33A32',
      success: (result) => {
        if (!result.confirm || this.data.deletingId) return
        this.setData({ deletingId: id })
        api.deleteBillingDocument(id).then(() => {
          localDraft.remove(localDraft.key(id))
          showToast('已删除', 'success')
          return this.load(true)
        }).catch((error) => showToast(error.message || '删除失败，请重试'))
          .finally(() => this.setData({ deletingId: null }))
      }
    })
  },

  openDocument(event) {
    if (this.data.managing) return this.toggleSelection(event)
    const id = event.currentTarget.dataset.id
    if (id) openPage('/pages/billing-editor/index', { id })
  },

  openCreate() {
    openPage('/pages/billing-editor/index', { documentType: this.data.documentType || '', factory: this.data.factory || '' })
  },

  onUnload() {
    clearTimeout(this.searchTimer)
    this.loadRequestId = (this.loadRequestId || 0) + 1
  }
})
