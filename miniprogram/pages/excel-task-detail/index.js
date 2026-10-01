const workflow = require('../../utils/excel-workflow')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: { task: null },
  onLoad(options) { this.id = options.id },
  onShow() {
    const task = workflow.getTask(this.id)
    if (!task) return showToast('文件任务不存在')
    this.setData({ task })
  },
  continueTask() {
    const task = this.data.task
    const path = task.status === 'MAPPING' ? '/pages/excel-mapping/index' : '/pages/excel-review/index'
    openPage(path, { id: task.id })
  },
  downloadResult() { showToast('结果文件已准备，可在正式接口接入后下载', 'none') },
  retryFailures() { showToast(`已提交 ${this.data.task.failures} 行失败数据重试`, 'success') },
  openPriceSync() { openPage('/pages/price-sync-review/index', { taskId: this.data.task.id }) },
  openVersionCompare() { openPage('/pages/excel-version-compare/index', { id: this.data.task.id }) },
  openTasks() { openPage('/pages/excel-tasks/index') }
})
