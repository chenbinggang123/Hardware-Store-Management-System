const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    username: '',
    password: '',
    loading: false
  },

  onUsernameInput(event) {
    this.setData({ username: event.detail.value })
  },

  onPasswordInput(event) {
    this.setData({ password: event.detail.value })
  },

  handleLogin() {
    if (!this.data.username || !this.data.password) {
      showToast('请输入账号和密码')
      return
    }
    this.setData({ loading: true })
    api.login({
      username: this.data.username,
      password: this.data.password
    }).then((session) => {
      getApp().setSession(session)
      showToast('登录成功', 'success')
      wx.switchTab({
        url: '/pages/home/index'
      })
    }).catch((error) => {
      const message = (error && (error.message || (error.data && error.data.message) || error.errMsg)) || '登录失败'
      showToast(message)
    }).finally(() => {
      this.setData({ loading: false })
    })
  }
})
