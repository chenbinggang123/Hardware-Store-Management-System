const api = require('../../utils/api')
const { TARGET_OPTIONS, normalizeTask, buildMappings } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

function errorMessage(error, fallback) {
  return (error && (error.message || (error.error && error.error.userMessage))) || fallback
}

Page({
  data: {
    id: '', task: null, sheet: null, mappings: [], visibleMappings: [], showAllFields: false,
    recognizedCount: 0, needsAttentionCount: 0, canMatchProducts: false,
    targetOptions: TARGET_OPTIONS.map((item) => item.label), loading: true, saving: false, skipping: false, loadError: ''
  },

  onLoad(options) {
    this.preferredSheetId = options.sheetId || ''
    this.setData({ id: options.id })
    this.loadTask()
  },

  loadTask() {
    this.setData({ loading: true, loadError: '' })
    return api.getExcelTask(this.data.id).then((detail) => {
      const task = normalizeTask(detail)
      if (task.status !== 'READY_FOR_MAPPING') {
        openPage(task.status === 'READY_FOR_REVIEW' ? '/pages/excel-review/index' : '/pages/excel-task-detail/index', { id: task.id }, { replace: true })
        return
      }
      const pdfSheets = detail.mimeType === 'application/pdf' && (detail.sheets || []).length
        && (detail.sheets || []).every((sheet) => JSON.stringify(sheet.columns || []) === JSON.stringify(['商品名称', '单位', '数量']))
      if (pdfSheets && !this.autoMapping) {
        this.autoMapping = true
        this.setData({ loading: true })
        return this.applyPdfMappings(detail, 0)
      }
      return Promise.all((detail.sheets || []).map((sheet) => api.getExcelReviewSummary(task.id, sheet.id).catch(() => null)))
        .then((summaries) => {
          const requestedIndex = (detail.sheets || []).findIndex((item) => String(item.id) === String(this.preferredSheetId))
          const index = requestedIndex >= 0 ? requestedIndex : Math.max(0, summaries.findIndex((item) => !item || !(item.mappings || []).length))
          const sheet = detail.sheets[index]
          if (!sheet) throw new Error('文件中没有可处理的工作表')
          const existing = summaries[index] && summaries[index].mappings
          return api.getExcelTaskRows(task.id, sheet.id, 0, 3).then((page) => {
            const mappings = buildMappings(sheet, page.items || [], existing, task.purposeCode)
            this.setData({ task, sheet, mappings, showAllFields: false }, () => this.updateVisibleMappings())
          })
        })
    }).catch((error) => this.setData({ loadError: errorMessage(error, '字段信息没有加载成功') }))
      .finally(() => this.setData({ loading: false }))
  },

  applyPdfMappings(detail, index) {
    const sheets = detail.sheets || []
    if (index >= sheets.length) {
      showToast('PDF 清单已识别，请核对商品', 'success')
      openPage('/pages/excel-review/index', { id: detail.id }, { replace: true })
      return Promise.resolve()
    }
    const sheet = sheets[index]
    return api.applyExcelMapping(detail.id, sheet.id, {
      expectedVersion: detail.version,
      mappings: [
        { sourceColumnIndex: 0, targetField: 'PRODUCT_NAME' },
        { sourceColumnIndex: 1, targetField: 'UNIT' },
        { sourceColumnIndex: 2, targetField: 'QUANTITY' }
      ]
    }).then(() => api.getExcelTask(detail.id)).then((next) => this.applyPdfMappings(next, index + 1))
      .catch((error) => {
        this.autoMapping = false
        throw error
      })
  },

  changeTarget(event) {
    const sourceColumnIndex = Number(event.currentTarget.dataset.sourceIndex)
    const mappings = this.data.mappings.slice()
    const index = mappings.findIndex((item) => item.sourceColumnIndex === sourceColumnIndex)
    if (index < 0) return
    const option = TARGET_OPTIONS[Number(event.detail.value)]
    mappings[index] = { ...mappings[index], target: option.label, targetCode: option.code, targetIndex: Number(event.detail.value), confidence: 100 }
    this.setData({ mappings }, () => this.updateVisibleMappings())
  },

  updateVisibleMappings() {
    const mappings = this.data.mappings || []
    const needsAttention = mappings.filter((item) => item.targetCode === 'IGNORE' || Number(item.confidence || 0) < 80)
    const canMatchProducts = mappings.some((item) => ['PRODUCT_NAME', 'BARCODE'].includes(item.targetCode))
    this.setData({
      visibleMappings: this.data.showAllFields || !canMatchProducts ? mappings : needsAttention,
      recognizedCount: mappings.length - needsAttention.length,
      needsAttentionCount: needsAttention.length,
      canMatchProducts
    })
  },

  toggleAllFields() {
    this.setData({ showAllFields: !this.data.showAllFields }, () => this.updateVisibleMappings())
  },

  skipSheet() {
    if (this.data.skipping || this.data.saving) return
    wx.showModal({
      title: '跳过这张工作表？',
      content: `“${this.data.sheet.sheetName}”将不参与商品匹配。适合说明、目录、空表或无效内容，其他工作表不受影响。`,
      confirmText: '确认跳过',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ skipping: true })
        api.skipExcelTaskSheet(this.data.id, this.data.sheet.id, this.data.task.version)
          .then(() => api.getExcelTask(this.data.id))
          .then((detail) => {
            if (detail.status === 'READY_FOR_REVIEW') {
              openPage('/pages/excel-review/index', { id: this.data.id }, { replace: true })
            } else {
              showToast('已跳过，请继续确认下一张表', 'success')
              this.loadTask()
            }
          }).catch((error) => showToast(errorMessage(error, '没有跳过成功，请重试')))
          .finally(() => this.setData({ skipping: false }))
      }
    })
  },

  continueReview() {
    if (this.data.saving) return
    const codes = this.data.mappings.map((item) => item.targetCode)
    if (!codes.includes('PRODUCT_NAME') && !codes.includes('BARCODE')) return showToast('商品名称或条码至少要选择一个')
    if (this.data.task.purposeCode === 'SUPPLIER_PRICE' && !codes.includes('UNIT_PRICE')) return showToast('厂家价格表必须选择单价列')
    if (['INVENTORY_COUNT', 'PURCHASE_RECEIPT'].includes(this.data.task.purposeCode) && !codes.includes('QUANTITY')) return showToast('库存或到货文件必须选择数量列')
    this.setData({ saving: true })
    api.applyExcelMapping(this.data.id, this.data.sheet.id, {
      expectedVersion: this.data.task.version,
      mappings: this.data.mappings.map((item) => ({ sourceColumnIndex: item.sourceColumnIndex, targetField: item.targetCode }))
    }).then(() => api.getExcelTask(this.data.id)).then((detail) => {
      if (detail.status === 'READY_FOR_REVIEW') {
        openPage('/pages/excel-review/index', { id: this.data.id }, { replace: true })
      } else {
        showToast('这一张表已确认，请继续下一张')
        this.loadTask()
      }
    }).catch((error) => showToast(errorMessage(error, '字段保存失败，请重试')))
      .finally(() => this.setData({ saving: false }))
  },

  retryLoad() { this.loadTask() }
})
