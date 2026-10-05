const api = require('../../utils/api')
const { TARGET_OPTIONS, normalizeTask, buildMappings } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

function errorMessage(error, fallback) {
  return (error && (error.message || (error.error && error.error.userMessage))) || fallback
}

Page({
  data: {
    id: '', task: null, sheet: null, mappings: [],
    targetOptions: TARGET_OPTIONS.map((item) => item.label), loading: true, saving: false, loadError: ''
  },

  onLoad(options) {
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
      return Promise.all((detail.sheets || []).map((sheet) => api.getExcelReviewSummary(task.id, sheet.id).catch(() => null)))
        .then((summaries) => {
          const index = Math.max(0, summaries.findIndex((item) => !item || !(item.mappings || []).length))
          const sheet = detail.sheets[index]
          if (!sheet) throw new Error('文件中没有可处理的工作表')
          const existing = summaries[index] && summaries[index].mappings
          return api.getExcelTaskRows(task.id, sheet.id, 0, 3).then((page) => {
            this.setData({ task, sheet, mappings: buildMappings(sheet, page.items || [], existing, task.purposeCode) })
          })
        })
    }).catch((error) => this.setData({ loadError: errorMessage(error, '字段信息没有加载成功') }))
      .finally(() => this.setData({ loading: false }))
  },

  changeTarget(event) {
    const index = Number(event.currentTarget.dataset.index)
    const mappings = this.data.mappings.slice()
    const option = TARGET_OPTIONS[Number(event.detail.value)]
    mappings[index] = { ...mappings[index], target: option.label, targetCode: option.code, confidence: 100 }
    this.setData({ mappings })
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
