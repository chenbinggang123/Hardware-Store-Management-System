const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

Page({
  data: {
    report: {
      purchase: 0,
      sales: 0,
      inventoryWarnings: 0
    },
    quickCharts: [
      { label: '采购趋势', value: '按日查看采购金额变化' },
      { label: '销售趋势', value: '跟踪销售总额和收款情况' },
      { label: '库存预警', value: '关注低库存与补货提醒' },
      { label: '客户结构', value: '对比 C 端、B 端和老客户占比' }
    ]
  },

  onShow() {
    api.getDailyReport().then((report) => {
      this.setData({ report })
    })
  },

  openReportCenter() {
    openPage('/pages/report-center/index')
  },

  openExport() {
    openPage('/pages/report-export/index')
  }
})
