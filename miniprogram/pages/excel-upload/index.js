const api = require('../../utils/api')
const { PURPOSE_OPTIONS, normalizeTask, taskRoute } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

function errorMessage(error, fallback) {
  return (error && (error.message || (error.error && error.error.userMessage) || error.errMsg)) || fallback
}

Page({
  data: {
    purposes: PURPOSE_OPTIONS.map((item) => item.label),
    purposeIndex: 0,
    suppliers: [],
    supplierNames: [],
    supplierIndex: -1,
    supplierLoadError: '',
    effectiveDate: '',
    file: null,
    recognizing: false,
    uploadError: ''
  },

  onLoad() {
    const now = new Date()
    const pad = (value) => `${value}`.padStart(2, '0')
    this.setData({ effectiveDate: `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` })
    this.loadSuppliers()
  },

  loadSuppliers() {
    this.setData({ supplierLoadError: '' })
    api.getSuppliers().then((suppliers) => {
      this.setData({ suppliers: suppliers || [], supplierNames: (suppliers || []).map((item) => item.name || `供应商 ${item.id}`) })
    }).catch((error) => this.setData({ supplierLoadError: (error && error.message) || '供应商没有加载成功' }))
  },
  retrySuppliers() { this.loadSuppliers() },

  chooseFile() {
    if (this.data.recognizing) return
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['doc', 'docx', 'xls', 'xlsx', 'csv', 'pdf', 'jpg', 'jpeg', 'png', 'webp'] }).then((result) => {
      const file = result.tempFiles && result.tempFiles[0]
      if (!file) return
      this.selectFile(file.name, file.size, file.path || file.tempFilePath, 'file')
    }).catch(() => {})
  },

  chooseImage() {
    if (this.data.recognizing) return
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['original', 'compressed'] })
      .then((result) => {
        const file = result.tempFiles && result.tempFiles[0]
        if (!file || !file.tempFilePath) return
        const matched = String(file.tempFilePath).match(/\.([a-zA-Z0-9]+)(?:\?|$)/)
        const extension = matched && ['jpg', 'jpeg', 'png', 'webp'].includes(matched[1].toLowerCase())
          ? matched[1].toLowerCase() : 'jpg'
        this.selectFile(`价格表-${Date.now()}.${extension}`, file.size, file.tempFilePath, 'image')
      }).catch(() => {})
  },

  selectFile(name, size, path, kind) {
    const matched = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/)
    const extension = matched ? matched[1] : ''
    const documentOnly = ['doc', 'docx', 'pdf'].includes(extension)
    this.setData({
      file: { name, size: Number(size || 0), sizeText: this.formatSize(size), path, kind, extension, documentOnly },
      purposeIndex: documentOnly ? 0 : this.data.purposeIndex,
      uploadError: ''
    })
  },

  formatSize(size) {
    const bytes = Number(size || 0)
    return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
  },

  changePurpose(event) {
    if (this.data.file && this.data.file.documentOnly) return showToast('Word 和 PDF 当前用于处理买家询价')
    this.setData({ purposeIndex: Number(event.detail.value), uploadError: '' })
  },
  changeSupplier(event) { this.setData({ supplierIndex: Number(event.detail.value), uploadError: '' }) },
  changeEffectiveDate(event) { this.setData({ effectiveDate: event.detail.value, uploadError: '' }) },

  startRecognition() {
    if (!this.data.file) return showToast('请先选择表格文件或价格表图片')
    if (this.data.recognizing) return
    const purpose = PURPOSE_OPTIONS[this.data.purposeIndex]
    if (purpose.code === 'SUPPLIER_PRICE' && this.data.supplierIndex < 0) return showToast('请选择这份价格表的供应商')
    const supplier = this.data.suppliers[this.data.supplierIndex]
    this.setData({ recognizing: true, uploadError: '' })
    api.uploadExcelTask(this.data.file, purpose.code, {
      supplierId: supplier && supplier.id,
      priceEffectiveDate: purpose.code === 'SUPPLIER_PRICE' ? this.data.effectiveDate : null
    }).then((result) => {
      const task = normalizeTask(result)
      if (task.status === 'FAILED') {
        this.setData({ uploadError: task.errorMessage || '文件读取失败，请检查表格后重试' })
        return
      }
      showToast(this.data.file.kind === 'image' ? '图片表格识别完成' : '文件读取完成', 'success')
      openPage(taskRoute(task), { id: task.id }, { replace: true })
    }).catch((error) => {
      this.setData({ uploadError: errorMessage(error, '文件上传失败，请检查网络后重试') })
    }).finally(() => this.setData({ recognizing: false }))
  }
})
