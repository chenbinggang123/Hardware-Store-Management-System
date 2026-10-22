const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

// 不常用、只给管理员用的功能都收在这里，侧栏只保留日常要用的入口。
const MORE_TOOLS = [
  { mark: '往', title: '往来账', desc: '旧的应收应付记录，只读核对用', path: '/pages/finance-ledger/index' },
  { mark: '客', title: '客户与进货厂家', desc: '维护名称、联系人和电话', path: '/pages/contacts/index' },
  { mark: '价', title: '价格变化记录', desc: '追溯进价和售价变化', path: '/pages/price-history/index' },
  { mark: '导', title: '批量导入', desc: '用 Excel 或价格表图片批量导入商品和价格', path: '/pages/excel-tasks/index' }
]

Page({
  data: {
    currentUser: {},
    userInitial: '管',
    isAdmin: false,
    moreTools: MORE_TOOLS,
    users: [],
    logs: []
  },

  onShow() {
    const currentUser = getApp().globalData.currentUser || {}
    const isAdmin = currentUser.role === 'ADMIN'
    this.setData({
      currentUser,
      isAdmin,
      userInitial: currentUser.name ? String(currentUser.name).slice(0, 1) : '管'
    })
    if (!isAdmin) return
    Promise.all([api.getUsers(), api.getLogs()]).then(([users, logs]) => {
      this.setData({
        users,
        logs: Array.isArray(logs) ? logs.slice(0, 4) : []
      })
    }).catch(() => {})
  },

  openTool(event) {
    const path = event.currentTarget.dataset.path
    if (path) openPage(path)
  },

  openUsers() {
    openPage('/pages/system-users/index')
  },

  openLogs() {
    openPage('/pages/system-logs/index')
  },

  logout() {
    api.logout().finally(() => {
      showToast('已退出登录', 'success')
      getApp().clearSession()
    })
  }
})
