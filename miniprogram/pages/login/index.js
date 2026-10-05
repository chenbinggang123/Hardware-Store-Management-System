const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    username: '',
    password: '',
    loading: false,
    showPassword: false,
    loginError: ''
  },

  onUsernameInput(event) {
    this.setData({ username: event.detail.value, loginError: '' })
  },

  onPasswordInput(event) {
    this.setData({ password: event.detail.value, loginError: '' })
  },

  togglePassword() { this.setData({ showPassword: !this.data.showPassword }) },

  handleLogin() {
    if (!this.data.username || !this.data.password) {
      this.setData({ loginError: '请输入账号和密码后再登录' })
      return
    }
    this.setData({ loading: true, loginError: '' })
    api.login({
      username: this.data.username,
      password: this.data.password
    }).then((session) => {
      getApp().setSession(session)
      showToast('登录成功', 'success')
      wx.switchTab({
        url: '/pages/workbench/index'
      })
    }).catch((error) => {
      const message = (error && (error.message || (error.data && error.data.message) || error.errMsg)) || '登录失败'
      this.setData({ loginError: `${message}。请检查账号、密码或网络后重试。` })
    }).finally(() => {
      this.setData({ loading: false })
    })
  }
})
