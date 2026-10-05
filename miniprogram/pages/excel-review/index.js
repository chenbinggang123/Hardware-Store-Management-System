const api = require('../../utils/api')
const { normalizeTask, normalizeReviewRow, mergeReviewSummaries } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

const filters = [
  { key: 'ALL', label: '全部' }, { key: 'MATCHED', label: '已匹配' }, { key: 'NEEDS_REVIEW', label: '待确认' },
  { key: 'READY_TO_CREATE', label: '可新增' }, { key: 'INVALID', label: '数据有误' }, { key: 'EXCLUDED', label: '已跳过' }
]

function errorMessage(error, fallback) {
  return (error && (error.message || (error.error && error.error.userMessage))) || fallback
}

Page({
  data: {
    id: '', task: null, sheet: null, sheetIndex: 0, sheetNames: [], filters, filter: 'ALL',
    rows: [], visibleRows: [], expandedId: null, remainingConflict: 0, summary: {},
    page: 0, totalPages: 0, totalElements: 0, hasNextPage: false, loading: true, reviewingId: null, loadError: ''
  },

  onLoad(options) {
    this.setData({ id: options.id })
    this.loadTask()
  },

  loadTask(preferredSheetIndex) {
    this.setData({ loading: true, loadError: '' })
    return api.getExcelTask(this.data.id).then((detail) => {
      const task = normalizeTask(detail)
      if (task.status === 'READY_FOR_MAPPING') {
        openPage('/pages/excel-mapping/index', { id: task.id }, { replace: true })
        return
      }
      const sheets = detail.sheets || []
      return Promise.all(sheets.map((sheet) => api.getExcelReviewSummary(task.id, sheet.id))).then((summaries) => {
        const summary = mergeReviewSummaries(summaries)
        const sheetIndex = Math.min(Number(preferredSheetIndex === undefined ? this.data.sheetIndex : preferredSheetIndex) || 0, Math.max(sheets.length - 1, 0))
        this.setData({ task: { ...task, ...summary }, summaries, summary, sheetNames: sheets.map((item) => item.sheetName), sheetIndex })
        return this.loadSheetPage(sheetIndex, 0)
      })
    }).catch((error) => this.setData({ loadError: errorMessage(error, '匹配结果没有加载成功') }))
      .finally(() => this.setData({ loading: false }))
  },

  loadSheetPage(sheetIndex, page) {
    const sheet = this.data.task.sheets[sheetIndex]
    if (!sheet) return Promise.resolve()
    return api.getExcelReviewRows(this.data.id, sheet.id, page, 30).then((result) => {
      const rows = (result.items || []).map(normalizeReviewRow)
      this.setData({ sheet, rows, page: result.page, totalPages: result.totalPages, totalElements: result.totalElements, hasNextPage: result.page + 1 < result.totalPages }, () => this.applyFilter())
    })
  },

  changeSheet(event) {
    const sheetIndex = Number(event.detail.value)
    this.setData({ sheetIndex, expandedId: null, filter: 'ALL', loading: true })
    this.loadSheetPage(sheetIndex, 0).catch((error) => this.setData({ loadError: errorMessage(error, '工作表没有加载成功') }))
      .finally(() => this.setData({ loading: false }))
  },

  selectFilter(event) {
    const filter = event.currentTarget.dataset.filter
    this.setData({ filter }, () => this.applyFilter())
  },

  applyFilter() {
    const visibleRows = this.data.filter === 'ALL' ? this.data.rows : this.data.rows.filter((item) => item.status === this.data.filter)
    const remainingConflict = Number(this.data.summary.needsReviewRows || 0) + Number(this.data.summary.invalidRows || 0)
    this.setData({ visibleRows, remainingConflict })
  },

  toggleRow(event) {
    const id = Number(event.currentTarget.dataset.id)
    this.setData({ expandedId: this.data.expandedId === id ? null : id })
  },

  confirmCreate(event) {
    this.reviewRow(Number(event.currentTarget.dataset.id), { decision: 'APPROVE_CREATE' })
  },

  selectCandidate(event) {
    this.reviewRow(Number(event.currentTarget.dataset.id), { decision: 'SELECT_PRODUCT', productId: Number(event.currentTarget.dataset.productId) })
  },

  skipRow(event) {
    const id = Number(event.currentTarget.dataset.id)
    wx.showModal({
      title: '本次跳过这一行？',
      content: '跳过后这一行不会写入商品、价格或库存，稍后仍可重新处理原文件。',
      confirmText: '确认跳过',
      success: (result) => { if (result.confirm) this.reviewRow(id, { decision: 'EXCLUDE', reason: '用户在小程序审核时选择跳过' }) }
    })
  },

  reviewRow(id, decision) {
    if (this.data.reviewingId) return
    this.setData({ reviewingId: id })
    api.reviewExcelRow(this.data.id, this.data.sheet.id, id, { expectedVersion: this.data.task.version, ...decision })
      .then(() => this.loadTask(this.data.sheetIndex))
      .then(() => showToast(decision.decision === 'EXCLUDE' ? '这一行已跳过' : '匹配结果已确认', 'success'))
      .catch((error) => showToast(errorMessage(error, '这一行没有保存成功，请重试')))
      .finally(() => this.setData({ reviewingId: null, expandedId: null }))
  },

  noop() {},

  openOriginal() {
    wx.showModal({ title: '原表辅助视图', content: '原表仅用于核对，可横向查看，但修改仍在行级审核卡中完成。', showCancel: false, confirmText: '知道了' })
  },

  loadNext() {
    if (this.data.page + 1 >= this.data.totalPages) return showToast('已经是最后一段')
    this.setData({ loading: true })
    this.loadSheetPage(this.data.sheetIndex, this.data.page + 1).finally(() => this.setData({ loading: false }))
  },

  previewChanges() {
    api.validateExcelTask(this.data.id, this.data.task.version).then((validation) => {
      if (!validation.valid) {
        wx.showModal({ title: '还不能进入确认', content: (validation.blockers || ['请先处理需要确认的数据行']).join('\n'), showCancel: false, confirmText: '知道了' })
        return
      }
      openPage('/pages/excel-confirm/index', { id: this.data.id })
    }).catch((error) => showToast(errorMessage(error, '校验失败，请稍后重试')))
  },

  retryLoad() { this.loadTask(this.data.sheetIndex) }
})
