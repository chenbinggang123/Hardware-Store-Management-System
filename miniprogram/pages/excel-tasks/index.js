const workflow = require('../../utils/excel-workflow')
const { openPage } = require('../../utils/router')

const filters = [
  { key: 'ALL', label: '全部' },
  { key: 'ATTENTION', label: '待处理' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'COMPLETED', label: '已完成' }
]

function matchesFilter(item, filter) {
  if (filter === 'ATTENTION') return ['MAPPING', 'REVIEW', 'CONFLICT'].includes(item.status)
  if (filter === 'PROCESSING') return item.status === 'PROCESSING'
  if (filter === 'COMPLETED') return item.status === 'COMPLETED'
  return true
}

Page({
  data: { filters, filter: 'ALL', tasks: [], visibleTasks: [], summary: {} },

  onShow() { this.loadTasks() },
  onPullDownRefresh() { this.loadTasks(); wx.stopPullDownRefresh() },

  loadTasks() {
    const tasks = workflow.getTasks()
    this.setData({ tasks, visibleTasks: tasks.filter((item) => matchesFilter(item, this.data.filter)), summary: workflow.getSummary() })
  },

  selectFilter(event) {
    const filter = event.currentTarget.dataset.filter
    this.setData({ filter, visibleTasks: this.data.tasks.filter((item) => matchesFilter(item, filter)) })
  },

  openUpload() { openPage('/pages/excel-upload/index') },

  openTask(event) {
    const task = workflow.getTask(event.currentTarget.dataset.id)
    if (!task) return
    const path = task.status === 'MAPPING' ? '/pages/excel-mapping/index' :
      ['REVIEW', 'CONFLICT'].includes(task.status) ? '/pages/excel-review/index' : '/pages/excel-task-detail/index'
    openPage(path, { id: task.id })
  }
})
