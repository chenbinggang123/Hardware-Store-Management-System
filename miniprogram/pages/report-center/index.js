const api = require('../../utils/api')

Page({
  data: {
    daily: null,
    monthly: null,
    yearly: null,
    purchaseReport: null,
    salesReport: null,
    inventoryReport: null
  },

  onShow() {
    Promise.all([
      api.getDailyReport(),
      api.getMonthlyReport(2026, 3),
      api.getYearlyReport(2026),
      api.getPurchaseReport(),
      api.getSalesReport(),
      api.getInventoryReport()
    ]).then(([daily, monthly, yearly, purchaseReport, salesReport, inventoryReport]) => {
      this.setData({
        daily,
        monthly,
        yearly,
        purchaseReport,
        salesReport,
        inventoryReport
      })
    })
  }
})
