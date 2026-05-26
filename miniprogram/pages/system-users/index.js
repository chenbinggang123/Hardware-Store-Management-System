const api = require('../../utils/api')

Page({
  data: {
    users: []
  },

  onShow() {
    api.getUsers().then((users) => {
      this.setData({ users })
    })
  }
})
