const workflow = require('../../utils/excel-workflow')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: { id: '', task: null, writing: false },

  onLoad(options) {
    const task = workflow.getTask(options.id)
    if (!task) return showToast('文件任务不存在')
    this.setData({ id: options.id, task })
  },

  confirmWrite() {
    if (this.data.writing) return
    this.setData({ writing: true })
    setTimeout(() => {
      workflow.completeTask(this.data.id)
      this.setData({ writing: false })
      openPage('/pages/excel-task-detail/index', { id: this.data.id }, { replace: true })
    }, 900)
  }
})
