const api = require('../../utils/api')
const { normalizeTask, mergeReviewSummaries, taskRoute } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: { task: null, loading: true, loadError: '' },
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
        this.setData({ task })
        if (task.status === 'PARSING') {
          this.stopPolling()
          this.pollTimer = setTimeout(() => this.loadTask(true), 1500)
        }
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
  reopenUpload() { openPage('/pages/excel-upload/index') },
  openTasks() { openPage('/pages/excel-tasks/index') },
  retryLoad() { this.loadTask() }
})
