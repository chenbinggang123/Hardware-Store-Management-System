const memory = require('../../utils/supply-chain-memory')
const { showToast } = require('../../utils/router')

function decorate(rows) {
  return rows.map((item) => ({
    ...item,
    changeText: item.change > 0 ? `+${item.change}%` : item.change < 0 ? `${item.change}%` : '新商品',
    direction: item.change > 0 ? 'up' : item.change < 0 ? 'down' : 'new'
  }))
}

Page({
  data: {
    filter: 'ALL',
    filters: [
      { key: 'ALL', label: '全部' },
      { key: 'NORMAL', label: '常规变动' },
      { key: 'ATTENTION', label: '需留意' },
      { key: 'SELECTED', label: '已勾选' }
    ],
    rows: [],
    visibleRows: [],
    selectedCount: 0,
    normalCount: 0,
    attentionCount: 0,
    confirmed: false
  },

  onShow() { this.loadRows() },

  loadRows() {
    const rows = decorate(memory.getPriceSyncRows())
    this.setData({ rows, confirmed: false }, () => this.refresh())
  },

  refresh() {
    const { rows, filter } = this.data
    let visibleRows = rows
    if (filter === 'NORMAL') visibleRows = rows.filter((item) => item.tone === 'normal')
    if (filter === 'ATTENTION') visibleRows = rows.filter((item) => item.tone !== 'normal')
    if (filter === 'SELECTED') visibleRows = rows.filter((item) => item.selected)
    this.setData({
      visibleRows,
      selectedCount: rows.filter((item) => item.selected).length,
      normalCount: rows.filter((item) => item.tone === 'normal').length,
      attentionCount: rows.filter((item) => item.tone !== 'normal').length
    })
  },

  changeFilter(event) {
    this.setData({ filter: event.currentTarget.dataset.key }, () => this.refresh())
  },

  toggleRow(event) {
    const id = Number(event.currentTarget.dataset.id)
    const rows = this.data.rows.map((item) => item.id === id ? { ...item, selected: !item.selected } : item)
    memory.savePriceSyncRows(rows)
    this.setData({ rows, confirmed: false }, () => this.refresh())
  },

  confirmSync() {
    const count = this.data.selectedCount
    if (!count) return showToast('请先勾选要更新的商品')
    wx.showModal({
      title: `更新 ${count} 条商品成本`,
      content: '只会更新已勾选商品的进价，不修改售价、库存和未匹配商品。是否继续？',
      confirmText: '确认更新',
      confirmColor: '#245E9B',
      success: (result) => {
        if (!result.confirm) return
        memory.savePriceSyncRows(this.data.rows)
        this.setData({ confirmed: true })
        showToast(`演示完成：已记录 ${count} 条选择`, 'success')
      }
    })
  }
})
