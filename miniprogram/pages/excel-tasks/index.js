const api = require('../../utils/api')
const { normalizeTask, taskRoute, taskSummary } = require('../../utils/excel-view')
const { openPage, showToast } = require('../../utils/router')

const filters = [
  { key: 'ALL', label: '全部' },
  { key: 'ATTENTION', label: '待处理' },
  { key: 'PROCESSING', label: '处理中' },
  { key: 'COMPLETED', label: '已完成' }
]

function matchesFilter(item, filter) {
  if (filter === 'ATTENTION') return ['READY_FOR_MAPPING', 'READY_FOR_REVIEW', 'FAILED'].includes(item.status)
  if (filter === 'PROCESSING') return item.status === 'PARSING'
  if (filter === 'COMPLETED') return ['COMMITTED', 'COMPLETED'].includes(item.status)
  return true
}

Page({
  data: { filters, filter: 'ALL', tasks: [], visibleTasks: [], summary: {}, loading: true, loadError: '' },

  onShow() { this.loadTasks() },
  onPullDownRefresh() { this.loadTasks(true) },

  loadTasks(stopRefresh) {
    this.setData({ loading: true, loadError: '' })
    return api.getExcelTasks().then((items) => {
      const tasks = (items || []).map(normalizeTask)
      this.setData({ tasks, visibleTasks: tasks.filter((item) => matchesFilter(item, this.data.filter)), summary: taskSummary(tasks) })
    }).catch((error) => {
      this.setData({ loadError: (error && error.message) || '文件任务暂时无法加载' })
    }).finally(() => {
      this.setData({ loading: false })
      if (stopRefresh) wx.stopPullDownRefresh()
    })
  },

  selectFilter(event) {
    const filter = event.currentTarget.dataset.filter
    this.setData({ filter, visibleTasks: this.data.tasks.filter((item) => matchesFilter(item, filter)) })
  },

  openUpload() { openPage('/pages/excel-upload/index') },

  openTask(event) {
    const task = this.data.tasks.find((item) => String(item.id) === String(event.currentTarget.dataset.id))
    if (!task) return
    openPage(taskRoute(task), { id: task.id })
  },

  retryLoad() { this.loadTasks() },
  showFailure(event) {
    const task = this.data.tasks.find((item) => String(item.id) === String(event.currentTarget.dataset.id))
    if (task && task.errorMessage) showToast(task.errorMessage)
  }
})
