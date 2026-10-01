const memory = require('../../utils/supply-chain-memory')

function decorate(items) {
  return items.map((item) => ({
    ...item,
    changeText: item.change > 0 ? `+${item.change}%` : `${item.change}%`,
    direction: item.change >= 0 ? 'up' : 'down'
  }))
}

Page({
  data: {
    productId: 2,
    productName: '冲击电钻',
    filter: '全部',
    filters: ['全部', '厂家成本', '批发价', '老客户价'],
    history: [],
    visibleHistory: []
  },
  onLoad(options) {
    const productId = Number(options.id || 2)
    this.setData({ productId, productName: options.name || '冲击电钻' })
    this.loadHistory(productId)
  },
  loadHistory(productId) {
    const history = decorate(memory.getPriceHistory(productId))
    this.setData({ history, visibleHistory: history })
  },
  changeFilter(event) {
    const filter = event.currentTarget.dataset.filter
    const visibleHistory = filter === '全部' ? this.data.history : this.data.history.filter((item) => item.type === filter)
    this.setData({ filter, visibleHistory })
  }
})
