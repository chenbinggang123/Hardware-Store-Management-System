const api = require('../../utils/api')
const { normalizeTask, taskRoute, taskSummary } = require('../../utils/excel-view')
const { openPage } = require('../../utils/router')

const quickActions = [
  { title: '开销售单', desc: '选客户和商品', mark: '单', tone: 'blue', path: '/pages/billing-editor/index' },
  { title: '查商品', desc: '看价格和库存', mark: '查', tone: 'green', path: '/pages/products/index' },
  { title: '采购进货', desc: '登记采购到货', mark: '入', tone: 'amber', path: '/pages/products/index?mode=factories' }
]

const toolActions = [
  { title: '工厂账本', desc: '记录每天拿货，月底自动算账', mark: '账', path: '/pages/factory-ledgers/index' },
  { title: '客户与供应商', desc: '查看联系人和往来资料', mark: '人', path: '/pages/contacts/index' },
  { title: '价格变化记录', desc: '追溯进价和售价变化', mark: '价', path: '/pages/price-history/index' },
  { title: '经营报表', desc: '查看和导出经营数据', mark: '表', path: '/pages/report-center/index' },
  { title: '采购单据', desc: '查看采购、到货和入库', mark: '购', path: '/pages/products/index?mode=factories' }
]

function timeLabel() {
  const now = new Date()
  const pad = (value) => value < 10 ? `0${value}` : String(value)
  return `${pad(now.getHours())}:${pad(now.getMinutes())}`
}

function dateLabel() {
  const now = new Date()
  const week = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][now.getDay()]
  return `${now.getMonth() + 1}月${now.getDate()}日 ${week}`
}

Page({
  data: {
    currentUser: {}, userInitial: '管', dashboardMeta: { status: '正在同步', updatedAt: '--:--' },
    summary: { attention: 0, processing: 0, failed: 0 }, priorityTasks: [],
    inventoryWarning: 0, quickActions, toolActions, todayText: dateLabel(),
    businessSummary: { orderCount: 0, salesAmount: '0.00' }, deletingTaskId: ''
  },

  onShow() {
    const app = getApp()
    const currentUser = app.globalData.currentUser || { name: '老板' }
    this.setData({ currentUser, userInitial: currentUser.name ? String(currentUser.name).slice(0, 1) : '管' })
    this.loadDashboard()
  },

  onPullDownRefresh() { this.loadDashboard(true) },

  loadDashboard(stopRefresh) {
    const safe = (promise) => promise.then((data) => ({ ok: true, data })).catch(() => ({ ok: false, data: [] }))
    Promise.all([safe(api.getInventoryWarnings()), safe(api.getSalesOrders()), safe(api.getExcelTasks())]).then(([warningResult, orderResult, taskResult]) => {
      const salesOrders = orderResult.data || []
      const tasks = (taskResult.data || []).map(normalizeTask)
      const salesAmount = salesOrders.reduce((sum, item) => sum + Number(item.totalAmount || 0), 0).toFixed(2)
      const allConnected = warningResult.ok && orderResult.ok && taskResult.ok
      this.setData({
        summary: taskSummary(tasks),
        priorityTasks: tasks.filter((item) => !['COMMITTED', 'COMPLETED'].includes(item.status)).slice(0, 4),
        inventoryWarning: (warningResult.data || []).length,
        businessSummary: { orderCount: salesOrders.length, salesAmount },
        dashboardMeta: { status: allConnected ? '业务数据已连接' : '部分数据暂不可用', updatedAt: timeLabel() }
      })
    }).finally(() => { if (stopRefresh) wx.stopPullDownRefresh() })
  },

  openPage(event) { openPage(event.currentTarget.dataset.path) },
  refreshDashboard() { this.loadDashboard() },
  openUpload() { openPage('/pages/excel-upload/index') },
  openTasks() { openPage('/pages/excel-tasks/index') },
  openTask(event) {
    const task = this.data.priorityTasks.find((item) => String(item.id) === String(event.currentTarget.dataset.id))
    if (!task) return
    openPage(taskRoute(task), { id: task.id })
  },
  deleteTask(event) {
    const task = this.data.priorityTasks.find((item) => String(item.id) === String(event.currentTarget.dataset.id))
    if (!task || !task.canDelete || this.data.deletingTaskId) return
    wx.showModal({
      title: '删除这个文件任务？',
      content: `将删除“${task.fileName}”及其字段映射、复核结果和生成文件。商品、库存、订单等业务数据不会受影响。`,
      confirmText: '删除任务',
      confirmColor: '#B94C3A',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ deletingTaskId: task.id })
        api.deleteExcelTask(task.id).then(() => {
          wx.showToast({ title: '任务已删除', icon: 'success' })
          return this.loadDashboard()
        }).catch((error) => wx.showToast({ title: (error && error.message) || '删除失败，请重试', icon: 'none' }))
          .finally(() => this.setData({ deletingTaskId: '' }))
      }
    })
  }
})
