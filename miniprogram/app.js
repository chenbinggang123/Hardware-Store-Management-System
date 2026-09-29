const AUTH_STORAGE_KEY = 'auth_session'
const cloudConfig = require('./config/cloud')

App({
  onLaunch() {
    if (cloudConfig.envId && wx.cloud) {
      wx.cloud.init({ env: cloudConfig.envId, traceUser: true })
    }
    this.restoreSession()
  },

  onShow() {
    this.ensureAuthenticated()
  },

  globalData: {
    brandName: '恒丰五金',
    currentUser: null,
    currentOperatorId: null,
    authToken: ''
  },

  restoreSession() {
    const session = wx.getStorageSync(AUTH_STORAGE_KEY)
    if (!session || !session.token || !session.user) {
      this.clearSession(false)
      return
    }
    this.globalData.authToken = session.token
    this.globalData.currentUser = session.user
    this.globalData.currentOperatorId = session.user.id || null
  },

  setSession(session) {
    wx.setStorageSync(AUTH_STORAGE_KEY, session)
    this.globalData.authToken = session.token
    this.globalData.currentUser = session.user
    this.globalData.currentOperatorId = session.user.id || null
  },

  clearSession(shouldRedirect = true) {
    wx.removeStorageSync(AUTH_STORAGE_KEY)
    this.globalData.authToken = ''
    this.globalData.currentUser = null
    this.globalData.currentOperatorId = null
    if (shouldRedirect) {
      wx.reLaunch({
        url: '/pages/login/index'
      })
    }
  },

  ensureAuthenticated() {
    const pages = getCurrentPages()
    const current = pages[pages.length - 1]
    const route = current && current.route
    if (!this.globalData.authToken && route && route !== 'pages/login/index') {
      wx.reLaunch({
        url: '/pages/login/index'
      })
    }
  }
})
