const memory = require('../../utils/supply-chain-memory')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: {
    comparison: null,
    filter: 'ALL',
    filters: [
      { key: 'ALL', label: '全部变化' },
      { key: 'CHANGED', label: '内容修改' },
      { key: 'ADDED', label: '新增行' }
    ],
    visibleRows: []
  },
  onLoad() {
    const comparison = memory.getVersionComparison()
    this.setData({ comparison, visibleRows: comparison.rows })
  },
  changeFilter(event) {
    const filter = event.currentTarget.dataset.key
    const rows = this.data.comparison.rows
    let visibleRows = rows
    if (filter === 'CHANGED') visibleRows = rows.filter((item) => item.tone === 'changed')
    if (filter === 'ADDED') visibleRows = rows.filter((item) => item.tone === 'added')
    this.setData({ filter, visibleRows })
  },
  continueWithCurrent() {
    showToast('已选择 V2 作为当前版本', 'success')
    setTimeout(() => openPage('/pages/excel-task-detail/index', { id: this.data.comparison.taskId }), 350)
  }
})
