const api = require('../../utils/api')

Page({
  data: {
    module: '',
    logs: []
  },

  onShow() {
    this.loadLogs()
  },

  selectModule(event) {
    this.setData({ module: event.currentTarget.dataset.module }, () => this.loadLogs())
  },

  loadLogs() {
    const params = {}
    if (this.data.module) {
      params.module = this.data.module
    }
    api.getLogs(params).then((logs) => {
      this.setData({ logs })
    })
  }
})
