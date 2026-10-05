const api = require('../../utils/api')
const { normalizeTask, mergeReviewSummaries, taskRoute } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: { task: null, outputs: [], downloadingId: '', loading: true, loadError: '' },
  onLoad(options) { this.id = options.id },
  onShow() { this.loadTask() },
  onHide() { this.stopPolling() },
  onUnload() { this.stopPolling() },
  loadTask() {
    this.setData({ loading: true, loadError: '' })
    return api.getExcelTask(this.id).then((detail) => {
      const requests = (detail.sheets || []).map((sheet) => api.getExcelReviewSummary(detail.id, sheet.id).catch(() => null))
      return Promise.all(requests).then((summaries) => {
        const summary = mergeReviewSummaries(summaries)
        const task = {
          ...normalizeTask(detail),
          matched: summary.matchedRows,
          additions: summary.readyToCreateRows,
          modifications: summary.matchedRows,
          skipped: summary.excludedRows,
          failures: summary.needsReviewRows + summary.invalidRows,
          logs: [
            { time: normalizeTask(detail).updatedAt, title: normalizeTask(detail).statusText, detail: normalizeTask(detail).progressText },
            { time: normalizeTask(detail).createdAt, title: '上传文件', detail: `原文件：${detail.originalName}` }
          ]
        }
        return api.getExcelOutputs(detail.id).catch(() => []).then((outputs) => {
          const normalizedOutputs = (outputs || []).map((item) => ({
            ...item,
            sizeText: this.formatSize(item.size),
            statusText: item.status === 'READY' ? '可以下载' : item.status === 'FAILED' ? '生成失败' : '正在生成',
            expiresText: item.expiresAt ? String(item.expiresAt).replace('T', ' ').slice(0, 16) : '--'
          }))
          this.setData({ task, outputs: normalizedOutputs })
          if (task.status === 'PARSING' || normalizedOutputs.some((item) => item.status === 'GENERATING')) {
            this.stopPolling()
            this.pollTimer = setTimeout(() => this.loadTask(true), 1500)
          }
        })
      })
    }).catch((error) => this.setData({ loadError: (error && error.message) || '文件任务没有加载成功' }))
      .finally(() => this.setData({ loading: false }))
  },
  stopPolling() {
    if (this.pollTimer) clearTimeout(this.pollTimer)
    this.pollTimer = null
  },
  continueTask() {
    const task = this.data.task
    openPage(taskRoute(task), { id: task.id })
  },
  formatSize(size) {
    const bytes = Number(size || 0)
    return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
  },
  openComparison() { openPage('/pages/excel-version-compare/index', { id: this.data.task.id }) },
  downloadOutput(event) {
    const output = this.data.outputs.find((item) => Number(item.id) === Number(event.currentTarget.dataset.id))
    if (!output || output.status !== 'READY' || this.data.downloadingId) return
    const safeName = String(output.name || `报价单-${output.id}.xlsx`).replace(/[\\/:*?"<>|]/g, '_')
    const filePath = `${wx.env.USER_DATA_PATH}/${Date.now()}-${safeName}`
    this.setData({ downloadingId: output.id })
    api.downloadExcelOutput(this.data.task.id, output.id).then((data) => new Promise((resolve, reject) => {
      wx.getFileSystemManager().writeFile({ filePath, data, success: resolve, fail: reject })
    })).then(() => wx.openDocument({ filePath, fileType: 'xlsx', showMenu: true }))
      .catch((error) => showToast((error && (error.message || error.errMsg)) || '文件下载失败，请稍后重试'))
      .finally(() => this.setData({ downloadingId: '' }))
  },
  reopenUpload() { openPage('/pages/excel-upload/index') },
  openTasks() { openPage('/pages/excel-tasks/index') },
  retryLoad() { this.loadTask() }
})
