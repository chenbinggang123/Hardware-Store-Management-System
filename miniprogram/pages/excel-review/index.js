const workflow = require('../../utils/excel-workflow')
const { openPage, showToast } = require('../../utils/router')

const filters = [
  { key: 'ALL', label: '全部' }, { key: 'MATCHED', label: '已匹配' }, { key: 'PENDING', label: '待确认' },
  { key: 'UNMATCHED', label: '未匹配' }, { key: 'CONFLICT', label: '冲突' }
]

Page({
  data: { id: '', task: null, filters, filter: 'ALL', rows: [], visibleRows: [], expandedId: null, remainingConflict: 0 },

  onLoad(options) {
    const task = workflow.getTask(options.id)
    if (!task) return showToast('文件任务不存在')
    const rows = task.rows && task.rows.length ? task.rows : workflow.getTask('EX-20260930-004').rows
    this.setData({ id: options.id, task, rows, visibleRows: rows, remainingConflict: rows.filter((item) => item.status === 'CONFLICT').length })
  },

  selectFilter(event) {
    const filter = event.currentTarget.dataset.filter
    this.setData({ filter, visibleRows: filter === 'ALL' ? this.data.rows : this.data.rows.filter((item) => item.status === filter) })
  },

  toggleRow(event) {
    const id = Number(event.currentTarget.dataset.id)
    this.setData({ expandedId: this.data.expandedId === id ? null : id })
  },

  confirmMatch(event) { this.updateRow(Number(event.currentTarget.dataset.id), { status: 'MATCHED', statusText: '已匹配', tone: 'success', note: '已由你确认匹配' }) },
  skipRow(event) { this.updateRow(Number(event.currentTarget.dataset.id), { status: 'SKIPPED', statusText: '已跳过', tone: 'neutral', note: '本次不会写入' }) },

  updateRow(id, patch) {
    const rows = this.data.rows.map((item) => item.id === id ? { ...item, ...patch } : item)
    const visibleRows = this.data.filter === 'ALL' ? rows : rows.filter((item) => item.status === this.data.filter)
    this.setData({ rows, visibleRows, expandedId: null, remainingConflict: rows.filter((item) => item.status === 'CONFLICT').length })
  },

  noop() {},

  openOriginal() {
    wx.showModal({ title: '原表辅助视图', content: '原表仅用于核对，可横向查看，但修改仍在行级审核卡中完成。', showCancel: false, confirmText: '知道了' })
  },

  loadNext() { showToast('下一段会继续保留当前筛选条件') },

  previewChanges() {
    const conflict = this.data.remainingConflict
    if (conflict) return showToast('还有冲突行需要先处理')
    const matched = this.data.rows.filter((item) => item.status === 'MATCHED').length
    const skipped = this.data.rows.filter((item) => item.status === 'SKIPPED').length
    workflow.updateTask(this.data.id, { rows: this.data.rows, matched, skipped, conflict: 0, modifications: matched })
    openPage('/pages/excel-confirm/index', { id: this.data.id })
  }
})
