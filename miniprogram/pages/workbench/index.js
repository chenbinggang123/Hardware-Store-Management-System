const api = require('../../utils/api')
const workflow = require('../../utils/excel-workflow')
const { openPage } = require('../../utils/router')

const supplyActions = [
  { title: '厂家价格', desc: '同步成本与价格历史', mark: '价', path: '/pages/excel-upload/index' },
  { title: '采购到货', desc: '查看采购与入库', mark: '到', path: '/pages/purchase-orders/index' },
  { title: '源头厂商', desc: '联系人与商品来源', mark: '厂', path: '/pages/contacts/index' }
]

const buyerActions = [
  { title: '询价与报价', desc: '上传买家需求表', mark: '询', path: '/pages/excel-upload/index' },
  { title: '销售订单', desc: '开单、收款与出库', mark: '单', path: '/pages/sales-orders/index' },
  { title: '买家资料', desc: '价格与往来记录', mark: '买', path: '/pages/contacts/index' }
]

function timeLabel() {
  const now = new Date()
  const pad = (value) => value < 10 ? `0${value}` : String(value)
  return `${pad(now.getHours())}:${pad(now.getMinutes())}`
}

Page({
  data: {
    currentUser: {}, userInitial: '管', dashboardMeta: { status: '正在同步', updatedAt: '--:--' },
    summary: { attention: 0, processing: 0, priceChanges: 0, unmatched: 0 }, priorityTasks: [],
    inventoryWarning: 0, supplyActions, buyerActions
  },

  onShow() {
    const app = getApp()
    const currentUser = app.globalData.currentUser || { name: '老板' }
    this.setData({ currentUser, userInitial: currentUser.name ? String(currentUser.name).slice(0, 1) : '管' })
    this.loadDashboard()
  },

  onPullDownRefresh() { this.loadDashboard(true) },

  loadDashboard(stopRefresh) {
    const tasks = workflow.getTasks()
    const summary = workflow.getSummary()
    this.setData({ summary, priorityTasks: tasks.filter((item) => item.status !== 'COMPLETED').slice(0, 4) })
    api.getInventoryWarnings().then((warnings) => {
      this.setData({ inventoryWarning: (warnings || []).length, dashboardMeta: { status: '业务数据已连接', updatedAt: timeLabel() } })
    }).catch(() => this.setData({ dashboardMeta: { status: '文件任务可用', updatedAt: timeLabel() } }))
      .finally(() => { if (stopRefresh) wx.stopPullDownRefresh() })
  },

  openPage(event) { openPage(event.currentTarget.dataset.path) },
  openUpload() { openPage('/pages/excel-upload/index') },
  openTasks() { openPage('/pages/excel-tasks/index') },
  openTask(event) {
    const task = workflow.getTask(event.currentTarget.dataset.id)
    if (!task) return
    const path = task.status === 'MAPPING' ? '/pages/excel-mapping/index' :
      ['REVIEW', 'CONFLICT'].includes(task.status) ? '/pages/excel-review/index' : '/pages/excel-task-detail/index'
    openPage(path, { id: task.id })
  }
})
