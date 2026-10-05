const api = require('../../utils/api')
const { normalizeTask, normalizeReviewRow } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

function errorMessage(error, fallback) {
  return (error && (error.message || (error.error && error.error.userMessage))) || fallback
}

function impactFor(purpose) {
  const impacts = {
    SUPPLIER_PRICE: { action: '更新商品进价', will: '只更新已匹配商品的进价', willNot: '不会修改零售价、库存和订单' },
    INVENTORY_COUNT: { action: '确认更新库存', will: '把库存调整为盘点后的实际数量', willNot: '不会修改商品价格和订单' },
    PURCHASE_RECEIPT: { action: '确认增加库存', will: '把本次到货数量增加到现有库存', willNot: '不会修改商品价格和采购单状态' },
    PRODUCT_IMPORT: { action: '确认更新商品资料', will: '新增或更新预览中的商品资料', willNot: '不会修改销售单和采购单' }
  }
  return impacts[purpose] || { action: '确认处理结果', will: '按预览内容处理数据', willNot: '不会修改预览之外的数据' }
}

Page({
  data: { id: '', task: null, validation: null, previewRows: [], impact: {}, writing: false, loading: true, loadError: '' },

  onLoad(options) {
    this.setData({ id: options.id })
    this.loadPreview()
  },

  loadPreview() {
    this.setData({ loading: true, loadError: '' })
    return api.getExcelTask(this.data.id).then((detail) => {
      const task = normalizeTask(detail)
      return api.validateExcelTask(task.id, task.version).then((validation) => {
        const requests = (detail.sheets || []).map((sheet) => api.getExcelReviewRows(task.id, sheet.id, 0, 5))
        return Promise.all(requests).then((pages) => {
          const previewRows = pages.flatMap((page) => page.items || []).filter((item) => item.matchStatus !== 'EXCLUDED').slice(0, 6).map(normalizeReviewRow)
          this.setData({
            task: {
              ...task,
              additions: Number(validation.readyToCreateRows || 0),
              modifications: Number(validation.matchedRows || 0),
              skipped: Number(validation.excludedRows || 0),
              failures: Number(validation.needsReviewRows || 0) + Number(validation.invalidRows || 0)
            },
            validation,
            previewRows,
            impact: impactFor(task.purposeCode)
          })
        })
      })
    }).catch((error) => this.setData({ loadError: errorMessage(error, '写入预览没有加载成功') }))
      .finally(() => this.setData({ loading: false }))
  },

  confirmWrite() {
    if (this.data.writing) return
    if (!this.data.validation || !this.data.validation.valid) return showToast('当前任务还不能正式写入')
    const count = this.data.task.additions + this.data.task.modifications
    wx.showModal({
      title: this.data.impact.action,
      content: `${this.data.impact.will}，共 ${count} 行。${this.data.impact.willNot}。是否继续？`,
      confirmText: this.data.impact.action,
      success: (result) => {
        if (!result.confirm) return
        this.setData({ writing: true })
        const idempotencyKey = `mini-${this.data.id}-${this.data.task.version}-${Date.now()}`
        api.commitExcelTask(this.data.id, this.data.task.version, idempotencyKey).then(() => {
          showToast('业务数据已更新', 'success')
          openPage('/pages/excel-task-detail/index', { id: this.data.id }, { replace: true })
        }).catch((error) => showToast(errorMessage(error, '没有完成写入，业务数据未改变')))
          .finally(() => this.setData({ writing: false }))
      }
    })
  },

  backToReview() { openPage('/pages/excel-review/index', { id: this.data.id }, { replace: true }) },
  retryLoad() { this.loadPreview() }
})
