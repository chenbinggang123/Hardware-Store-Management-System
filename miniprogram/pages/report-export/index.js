const api = require('../../utils/api')
const { formatDate } = require('../../utils/format')
const { showToast } = require('../../utils/router')

Page({
  data: {
    typeOptions: ['daily', 'monthly', 'sales', 'purchases', 'inventories'],
    typeIndex: 0,
    startDate: formatDate(),
    endDate: formatDate(),
    result: null
  },

  changeType(event) {
    this.setData({ typeIndex: Number(event.detail.value) })
  },

  changeDate(event) {
    const key = event.currentTarget.dataset.key
    this.setData({ [key]: event.detail.value })
  },

  exportNow() {
    const type = this.data.typeOptions[this.data.typeIndex]
    api.exportReport(type, {
      startDate: this.data.startDate,
      endDate: this.data.endDate
    }).then((result) => {
      this.setData({ result })
      showToast('导出结果已生成', 'success')
    })
  }
})
