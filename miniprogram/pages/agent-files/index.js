const api = require('../../utils/api')
const { openAgentFile, confirmDeleteAgentFile, askAgentToModify } = require('../../utils/agent-files')
const { openPage, showToast } = require('../../utils/router')
const { taskRoute } = require('../../utils/excel-view')

const ACTIVE_CONVERSATION_KEY = 'agent_active_conversation_id'

function errorText(error, fallback) {
  return (error && (error.message || error.errMsg)) || fallback
}

function timeText(value) {
  if (!value) return '时间未知'
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

function sizeText(value) {
  const bytes = Number(value || 0)
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function itemView(item) {
  const stateText = { PENDING: '待处理', COMPLETED: '已完成', FAILED: '失败' }[item.state] || '处理中'
  const mime = String(item.mimeType || '').toLowerCase()
  const fileType = mime.includes('pdf') || String(item.name || '').toLowerCase().endsWith('.pdf') ? 'pdf' : 'xlsx'
  const mark = item.kind === 'OUTPUT' ? '成' : item.kind === 'TASK' ? '理' : mime.startsWith('image/') ? '图' : '原'
  return {
    ...item,
    mark,
    fileType,
    timeText: timeText(item.updateTime || item.createTime),
    sizeText: sizeText(item.size),
    stateText,
    stateTone: item.state === 'FAILED' ? 'danger' : item.state === 'COMPLETED' ? 'success' : 'info',
    openText: item.kind === 'TASK' ? '继续处理' : item.kind === 'ATTACHMENT' ? '查看对话' : '打开文件',
    ready: item.kind === 'OUTPUT' && item.sourceStatus === 'READY',
    failed: item.state === 'FAILED',
    sourceType: item.kind === 'OUTPUT' ? 'AGENT_WORKBOOK' : item.kind,
    status: item.sourceStatus
  }
}

Page({
  data: {
    items: [], visible: [], state: 'ALL', keyword: '',
    counts: { total: 0, pending: 0, completed: 0, failed: 0 },
    loading: true, loadError: '', openingKey: '', deletingKey: ''
  },

  onShow() { this.loadFiles() },
  onPullDownRefresh() { this.loadFiles(true) },

  loadFiles(stopRefresh) {
    this.setData({ loading: true, loadError: '' })
    return api.getFileCenter().then((result) => {
      const view = result || {}
      const items = (view.items || []).map(itemView)
      this.setData({
        items,
        counts: {
          total: Number(view.total || 0), pending: Number(view.pending || 0),
          completed: Number(view.completed || 0), failed: Number(view.failed || 0)
        }
      }, () => this.applyFilter())
    }).catch((error) => {
      this.setData({ loadError: errorText(error, '文件列表暂时无法加载') })
    }).finally(() => {
      this.setData({ loading: false })
      if (stopRefresh) wx.stopPullDownRefresh()
    })
  },

  applyFilter() {
    const keyword = String(this.data.keyword || '').trim().toLowerCase()
    this.setData({
      visible: this.data.items.filter((item) => {
        const matchesState = this.data.state === 'ALL' || item.state === this.data.state
        const matchesKeyword = !keyword || `${item.name || ''} ${item.purpose || ''} ${item.errorMessage || ''}`.toLowerCase().includes(keyword)
        return matchesState && matchesKeyword
      })
    })
  },

  selectState(event) { this.setData({ state: event.currentTarget.dataset.state }, () => this.applyFilter()) },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }, () => this.applyFilter()) },
  clearKeyword() { this.setData({ keyword: '' }, () => this.applyFilter()) },
  retryLoad() { this.loadFiles() },
  openAgent() { openPage('/pages/agent/index') },

  openItem(event) {
    const item = this.data.items.find((entry) => entry.key === event.currentTarget.dataset.key)
    if (!item || this.data.openingKey) return
    if (item.kind === 'TASK') return openPage(taskRoute({ status: item.sourceStatus }), { id: item.id })
    if (item.kind === 'ATTACHMENT') {
      if (item.conversationId) wx.setStorageSync(ACTIVE_CONVERSATION_KEY, item.conversationId)
      return wx.switchTab({ url: '/pages/agent/index' })
    }
    if (!item.ready) return showToast(item.failed ? '这个文件已失效或生成失败' : '文件还在生成中')
    this.setData({ openingKey: item.key })
    openAgentFile(item).catch((error) => showToast(errorText(error, '文件打开失败，请稍后重试')))
      .finally(() => this.setData({ openingKey: '' }))
  },

  modifyFile(event) {
    const item = this.data.items.find((entry) => entry.key === event.currentTarget.dataset.key)
    if (item && item.ready) askAgentToModify(item, openPage)
  },

  deleteFile(event) {
    const item = this.data.items.find((entry) => entry.key === event.currentTarget.dataset.key)
    if (!item || item.kind !== 'OUTPUT' || this.data.deletingKey) return
    this.setData({ deletingKey: item.key })
    confirmDeleteAgentFile(item).then((deleted) => {
      if (!deleted) return
      const items = this.data.items.filter((entry) => entry.key !== item.key)
      this.setData({ items, counts: { ...this.data.counts, total: Math.max(this.data.counts.total - 1, 0), completed: Math.max(this.data.counts.completed - 1, 0) } }, () => this.applyFilter())
      showToast('文件已删除', 'success')
    }).catch((error) => showToast(errorText(error, '删除失败，请重试')))
      .finally(() => this.setData({ deletingKey: '' }))
  }
})
