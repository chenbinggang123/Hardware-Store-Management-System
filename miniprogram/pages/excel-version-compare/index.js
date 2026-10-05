const api = require('../../utils/api')
const { normalizeTask } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

const FIELD_LABELS = { PRODUCT_NAME: '商品名称', BARCODE: '条码', SUPPLIER_SKU: '厂家货号', SPEC: '规格', UNIT: '单位', UNIT_PRICE: '单价', QUANTITY: '数量' }
const TYPE_LABELS = { CHANGED: '内容修改', ADDED: '新增行', REMOVED: '删除行', UNCHANGED: '未变化', ISSUE: '需要确认' }

function valueText(value) {
  if (value === null || value === undefined || value === '') return '未填写'
  if (typeof value === 'object') {
    return Object.keys(value).slice(0, 3).map((key) => `${FIELD_LABELS[key] || key}：${value[key] || '未填写'}`).join('，')
  }
  return String(value)
}

function normalizeItem(item) {
  let changeLines = []
  if (item.changeType === 'CHANGED') {
    changeLines = Object.keys(item.changes || {}).map((key) => ({
      key, label: FIELD_LABELS[key] || key,
      before: valueText(item.changes[key] && item.changes[key].before),
      after: valueText(item.changes[key] && item.changes[key].after),
      percent: item.changes[key] && item.changes[key].changePercent !== null && item.changes[key].changePercent !== undefined
        ? `${Number(item.changes[key].changePercent) > 0 ? '+' : ''}${Number(item.changes[key].changePercent).toFixed(1)}%` : ''
    }))
  } else if (item.changeType === 'ADDED') {
    changeLines = [{ key: 'added', label: '新增内容', before: '原版本没有', after: valueText(item.changes && item.changes.after), percent: '' }]
  } else if (item.changeType === 'REMOVED') {
    changeLines = [{ key: 'removed', label: '删除内容', before: valueText(item.changes && item.changes.before), after: '新版本没有', percent: '' }]
  }
  return {
    ...item,
    typeLabel: TYPE_LABELS[item.changeType] || item.changeType,
    tone: String(item.changeType || '').toLowerCase(),
    rowText: `原表 ${item.baseRowNumber || '—'} 行 · 新表 ${item.newRowNumber || '—'} 行`,
    title: item.productName || item.matchKey || '未命名行',
    changeLines
  }
}

Page({
  data: {
    currentTask: null, candidates: [], candidateLabels: [], candidateIndex: -1, selectedBase: null,
    comparison: null, loading: true, creating: false, loadError: '',
    filter: 'CHANGED', page: 0, hasMore: false, loadingMore: false,
    filters: [
      { key: '', label: '全部结果' }, { key: 'CHANGED', label: '内容修改' },
      { key: 'ADDED', label: '新增行' }, { key: 'REMOVED', label: '删除行' },
      { key: 'ISSUE', label: '需要确认' }, { key: 'UNCHANGED', label: '未变化' }
    ],
    visibleRows: []
  },
  onLoad(options) {
    this.taskId = options.id
    this.loadSetup()
  },
  onHide() { this.stopPolling() },
  onUnload() { this.stopPolling() },
  loadSetup() {
    if (!this.taskId) return this.setData({ loading: false, loadError: '缺少当前文件任务编号' })
    this.setData({ loading: true, loadError: '' })
    Promise.all([api.getExcelTask(this.taskId), api.getExcelTasks()]).then(([detail, tasks]) => {
      const currentTask = normalizeTask(detail)
      const candidates = (tasks || []).map(normalizeTask).filter((item) => Number(item.id) !== Number(currentTask.id)
        && item.purposeCode === currentTask.purposeCode && ['READY_FOR_REVIEW', 'COMMITTED'].includes(item.status))
      this.setData({ currentTask, candidates, candidateLabels: candidates.map((item) => `${item.fileName} · ${item.rowCount} 行 · ${item.updatedAt}`), loading: false })
    }).catch((error) => this.setData({ loading: false, loadError: (error && error.message) || '可比较文件没有加载成功' }))
  },
  changeCandidate(event) {
    const candidateIndex = Number(event.detail.value)
    this.setData({ candidateIndex, selectedBase: this.data.candidates[candidateIndex] || null })
  },
  startComparison() {
    const base = this.data.selectedBase
    if (!base) return showToast('请先选择一个原版本')
    this.setData({ creating: true, loadError: '' })
    api.getExcelTask(base.id).then((baseDetail) => api.createExcelComparison({
      baseTaskId: base.id, baseTaskVersion: baseDetail.version,
      newTaskId: this.data.currentTask.id, newTaskVersion: this.data.currentTask.version,
      matchKeys: ['BARCODE'], compareFields: ['PRODUCT_NAME', 'SPEC', 'UNIT', 'UNIT_PRICE', 'QUANTITY'],
      idempotencyKey: `compare-${base.id}-${this.data.currentTask.id}-${Date.now()}`
    })).then((comparison) => {
      this.setData({ comparison })
      this.pollComparison()
    }).catch((error) => this.setData({ loadError: (error && error.message) || '比较任务没有创建成功' }))
      .finally(() => this.setData({ creating: false }))
  },
  pollComparison() {
    const id = this.data.comparison && this.data.comparison.id
    if (!id) return
    api.getExcelComparison(id).then((comparison) => {
      this.setData({ comparison })
      if (['QUEUED', 'RUNNING'].includes(comparison.status)) {
        this.stopPolling()
        this.pollTimer = setTimeout(() => this.pollComparison(), 1200)
      } else if (['COMPLETED', 'PARTIAL'].includes(comparison.status)) this.loadItems(true)
    }).catch((error) => this.setData({ loadError: (error && error.message) || '比较进度没有加载成功' }))
  },
  stopPolling() { if (this.pollTimer) clearTimeout(this.pollTimer); this.pollTimer = null },
  loadItems(reset = false) {
    if (!this.data.comparison || this.data.loadingMore) return
    const page = reset ? 0 : this.data.page
    this.setData({ loadingMore: true })
    api.getExcelComparisonItems(this.data.comparison.id, this.data.filter, page, 40).then((result) => {
      const next = (result.items || []).map(normalizeItem)
      this.setData({ visibleRows: reset ? next : this.data.visibleRows.concat(next), page: page + 1, hasMore: page + 1 < Number(result.totalPages || 0) })
    }).catch((error) => showToast((error && error.message) || '比较结果没有加载成功'))
      .finally(() => this.setData({ loadingMore: false }))
  },
  changeFilter(event) {
    const filter = event.currentTarget.dataset.key
    this.setData({ filter, visibleRows: [], page: 0 }, () => this.loadItems(true))
  },
  loadMore() { this.loadItems(false) },
  cancelComparison() {
    wx.showModal({ title: '取消版本比较', content: '已生成的比较结果不会写入业务数据。确认停止当前比较吗？', confirmText: '确认取消' }).then((result) => {
      if (!result.confirm) return
      return api.cancelExcelComparison(this.data.comparison.id).then((comparison) => { this.stopPolling(); this.setData({ comparison }); showToast('比较已取消') })
    }).catch(() => {})
  },
  openProduct(event) { if (event.currentTarget.dataset.id) openPage('/pages/product-detail/index', { id: event.currentTarget.dataset.id }) },
  returnToTask() { openPage('/pages/excel-task-detail/index', { id: this.data.currentTask.id }, { replace: true }) },
  retryLoad() { this.loadSetup() }
})
