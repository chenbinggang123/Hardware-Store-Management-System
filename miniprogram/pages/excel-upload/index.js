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
    file: null,
    recognizing: false,
    uploadError: ''
  },

  chooseFile() {
    if (this.data.recognizing) return
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['xls', 'xlsx', 'csv'] }).then((result) => {
      const file = result.tempFiles && result.tempFiles[0]
      if (!file) return
      this.setData({ file: { name: file.name, size: Number(file.size || 0), sizeText: this.formatSize(file.size), path: file.path || file.tempFilePath }, uploadError: '' })
    }).catch(() => {})
  },

  formatSize(size) {
    const bytes = Number(size || 0)
    return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
  },

  changePurpose(event) { this.setData({ purposeIndex: Number(event.detail.value), uploadError: '' }) },

  startRecognition() {
    if (!this.data.file) return showToast('请先选择 Excel 或 CSV 文件')
    if (this.data.recognizing) return
    const purpose = PURPOSE_OPTIONS[this.data.purposeIndex]
    this.setData({ recognizing: true, uploadError: '' })
    api.uploadExcelTask(this.data.file, purpose.code).then((result) => {
      const task = normalizeTask(result)
      if (task.status === 'FAILED') {
        this.setData({ uploadError: task.errorMessage || '文件读取失败，请检查表格后重试' })
        return
      }
      showToast('文件读取完成', 'success')
      openPage(taskRoute(task), { id: task.id }, { replace: true })
    }).catch((error) => {
      this.setData({ uploadError: errorMessage(error, '文件上传失败，请检查网络后重试') })
    }).finally(() => this.setData({ recognizing: false }))
  }
})
