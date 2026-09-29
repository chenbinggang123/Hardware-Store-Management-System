const api = require('../../utils/api')

Page({
  data: {
    users: []
  },

  onShow() {
    api.getUsers().then((users) => {
      this.setData({
        users: (users || []).map((item) => ({
          ...item,
          initial: item.name ? String(item.name).slice(0, 1) : '人'
        }))
      })
    })
  }
})
