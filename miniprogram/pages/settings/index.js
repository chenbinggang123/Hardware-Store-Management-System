const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: {
    currentUser: {},
    userInitial: '管',
    users: [],
    logs: [],
    lastBackup: null
  },

  onLoad() {
    const app = getApp()
    const currentUser = app.globalData.currentUser || {}
    this.setData({
      currentUser,
      userInitial: currentUser.name ? String(currentUser.name).slice(0, 1) : '管'
    })
  },

  onShow() {
    const app = getApp()
    const currentUser = app.globalData.currentUser || {}
    this.setData({
      currentUser,
      userInitial: currentUser.name ? String(currentUser.name).slice(0, 1) : '管'
    })
    Promise.all([api.getUsers(), api.getLogs()]).then(([users, logs]) => {
      this.setData({
        users,
        logs: Array.isArray(logs) ? logs.slice(0, 4) : []
      })
    })
  },

  openUsers() {
    openPage('/pages/system-users/index')
  },

  openLogs() {
    openPage('/pages/system-logs/index')
  },

  backupNow() {
    api.backupSystem().then((result) => {
      this.setData({ lastBackup: result })
      showToast('备份完成', 'success')
    })
  },

  restoreLast() {
    const backupName = (this.data.lastBackup && this.data.lastBackup.backupName) || 'latest-backup'
    api.restoreSystem(backupName).then(() => {
      showToast('恢复结果已返回', 'success')
    })
  },

  logout() {
    api.logout().finally(() => {
      showToast('已退出登录', 'success')
      getApp().clearSession()
    })
  }
})
