const { openPage } = require('../../utils/router')
const api = require('../../utils/api')

const directActions = [
  { title: '开销售单', mark: '单', tone: 'sales', desc: '选客户和商品', path: '/pages/sales-form/index' },
  { title: '查商品', mark: '查', tone: 'search', desc: '看价格和库存', path: '/pages/products/index' },
  { title: '采购入库', mark: '入', tone: 'stock-in', desc: '到货后登记', path: '/pages/purchase-orders/index' },
  { title: '库存盘点', mark: '盘', tone: 'count', desc: '核对实际数量', path: '/pages/inventories/index' }
]

const moreFunctions = [
  { title: '采购', mark: '采', path: '/pages/purchase-orders/index' },
  { title: '客户', mark: '客', path: '/pages/customers/index' },
  { title: '供应商', mark: '供', path: '/pages/suppliers/index' },
  { title: '报表', mark: '表', path: '/pages/report-center/index' },
  { title: '导出', mark: '出', path: '/pages/report-export/index' },
  { title: '设置', mark: '设', path: '/pages/settings/index' }
]

function getTimeLabel() {
  const now = new Date()
  const pad = (value) => value < 10 ? `0${value}` : String(value)
  return `${pad(now.getHours())}:${pad(now.getMinutes())}`
}

Page({
  data: {
    directActions,
    moreFunctions,
    currentUser: {},
    userInitial: '管',
    stats: { productCount: 0, purchaseCount: 0, salesCount: 0, warningCount: 0 },
    report: { sales: 0, purchase: 0 },
    recentLogs: [],
    dashboardMeta: { status: '正在同步数据', updatedAt: '--:--' }
  },

  onShow() {
    const app = getApp()
    const currentUser = app.globalData.currentUser || { name: '老板', role: '管理员' }
    this.setData({
      currentUser,
      userInitial: currentUser.name ? String(currentUser.name).slice(0, 1) : '管'
    })
    this.loadDashboard()
  },

  onPullDownRefresh() {
    this.loadDashboard(true)
  },

  loadDashboard(stopPullDownRefresh) {
    Promise.all([
      api.getProducts(),
      api.getPurchaseOrders(),
      api.getSalesOrders(),
      api.getDailyReport(),
      api.getLogs()
    ]).then(([products, purchases, sales, report, logs]) => {
      const warnings = (products || []).filter(p => (p.stock || 0) <= 10).length
      this.setData({
        stats: {
          productCount: (products || []).length,
          purchaseCount: (purchases || []).length,
          salesCount: (sales || []).length,
          warningCount: warnings
        },
        report: report || { sales: 0, purchase: 0 },
        recentLogs: (logs || []).slice(0, 5),
        dashboardMeta: { status: '数据已更新', updatedAt: getTimeLabel() }
      })
    }).catch(() => {
      this.setData({ dashboardMeta: { status: '暂时无法更新', updatedAt: getTimeLabel() } })
    }).finally(() => {
      if (stopPullDownRefresh) wx.stopPullDownRefresh()
    })
  },

  openModule(event) {
    const { path } = event.currentTarget.dataset
    openPage(path)
  }
})
