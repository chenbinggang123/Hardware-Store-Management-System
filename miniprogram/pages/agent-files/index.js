const api = require('../../utils/api')
const { openAgentFile, confirmDeleteAgentFile, askAgentToModify } = require('../../utils/agent-files')
const { openPage, showToast } = require('../../utils/router')
const { taskRoute } = require('../../utils/excel-view')

const ACTIVE_CONVERSATION_KEY = 'agent_active_conversation_id'
const PURPOSES = [
  { code: 'CUSTOMER_RECEIPT', label: '客户单据' },
  { code: 'SUPPLIER_RECEIPT', label: '厂商单据' },
  { code: 'PRICE_LIST', label: '商品价格表' },
  { code: 'PRODUCT_IMPORT', label: '商品资料' },
  { code: 'INVENTORY_COUNT', label: '库存盘点' },
  { code: 'UNCLASSIFIED', label: '其他文件' }
]

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
    relationText: item.relatedName ? `关联：${item.relatedName}` : '',
    sourceType: item.kind === 'OUTPUT' ? 'AGENT_WORKBOOK' : item.kind,
    status: item.sourceStatus
  }
}

Page({
  data: {
    items: [], visible: [], state: 'ALL', keyword: '',
    counts: { total: 0, pending: 0, completed: 0, failed: 0 },
    loading: true, loadError: '', openingKey: '', deletingKey: '', uploading: false
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
        const matchesKeyword = !keyword || `${item.name || ''} ${item.purpose || ''} ${item.relatedName || ''} ${item.errorMessage || ''}`.toLowerCase().includes(keyword)
        return matchesState && matchesKeyword
      })
    })
  },

  selectState(event) { this.setData({ state: event.currentTarget.dataset.state }, () => this.applyFilter()) },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }, () => this.applyFilter()) },
  clearKeyword() { this.setData({ keyword: '' }, () => this.applyFilter()) },
  retryLoad() { this.loadFiles() },
  openAgent() { openPage('/pages/agent/index') },

  addFile() {
    if (this.data.uploading) return
    wx.showActionSheet({ itemList: ['拍照或从相册选择', '从聊天文件选择'] }).then((choice) => {
      if (choice.tapIndex === 0) {
        return wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'] })
          .then((result) => {
            const item = (result.tempFiles || [])[0]
            return item ? { path: item.tempFilePath, size: item.size, name: `单据照片-${Date.now()}.${item.fileType || 'jpg'}` } : null
          })
      }
      return wx.chooseMessageFile({ count: 1, type: 'file', extension: ['jpg', 'jpeg', 'png', 'webp', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv'] })
        .then((result) => (result.tempFiles || [])[0] || null)
    }).then((file) => {
      if (!file) return null
      return wx.showActionSheet({ itemList: PURPOSES.map(item => item.label) })
        .then((choice) => ({ file, purpose: PURPOSES[choice.tapIndex] }))
    }).then((selection) => {
      if (!selection) return null
      this.setData({ uploading: true })
      let attachment = null
      return api.uploadAgentAttachment(selection.file, null)
        .then((result) => {
          attachment = result
          return api.organizeFile('ATTACHMENT', result.id, {
            purposeCode: selection.purpose.code,
            relatedType: 'NONE',
            manualUpload: true
          })
        })
        .then(() => {
          showToast('文件已添加', 'success')
          return this.loadFiles()
        })
        .catch((error) => {
          if (attachment) {
            this.loadFiles()
            return showToast('文件已上传，用途可稍后整理')
          }
          return showToast(errorText(error, '文件添加失败，请重试'))
        })
        .finally(() => this.setData({ uploading: false }))
    }).catch((error) => {
      if (!String((error && error.errMsg) || '').includes('cancel')) showToast(errorText(error, '没有完成文件选择'))
    })
  },

  organizeFile(event) {
    const item = this.data.items.find((entry) => entry.key === event.currentTarget.dataset.key)
    if (item) openPage('/pages/file-detail/index', { kind: item.kind, id: item.id })
  },

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
