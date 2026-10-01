const api = require('../../utils/api')
const { formatAgentContent } = require('../../utils/agent-format')
const { openPage } = require('../../utils/router')

const ACTIVE_RUN_KEY = 'agent_active_run_id'
const ACTIVE_CONVERSATION_KEY = 'agent_active_conversation_id'

function errorMessage(error, fallback) {
  return (error && (error.message || error.errMsg)) || fallback
}

function normalizeResult(result) {
  if (!result) return null
  const action = result.pendingAction
  const preview = action && action.preview ? action.preview : null
  return {
    ...result,
    statusText: {
      WAITING_APPROVAL: '等待你确认', COMPLETED: '已完成', FAILED: '执行失败',
      CANCELLED: '已取消', EXPIRED: '已过期', RUNNING: '处理中'
    }[result.status] || result.status,
    pendingAction: action ? {
      ...action,
      isHighRisk: action.risk === 'R2_WRITE',
      preview: preview ? { ...preview, items: preview.items || [] } : { items: [] }
    } : null
  }
}

function assistantMessage(content, status) {
  return {
    role: status === 'FAILED' ? 'error' : 'assistant',
    content,
    blocks: formatAgentContent(content)
  }
}

function historyTime(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

function conversationMessage(message) {
  if (message.role === 'assistant') return assistantMessage(message.content, message.status)
  return { role: 'user', content: message.content, attachments: (message.attachments || []).map(attachmentView) }
}

function fileSize(value) {
  const bytes = Number(value) || 0
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function attachmentView(item) {
  const name = item.originalName || item.name || '附件'
  return {
    ...item,
    originalName: name,
    sizeText: fileSize(item.fileSize || item.size),
    typeText: /image\//.test(item.mimeType || '') ? '图片' : '表格',
    ready: item.parseStatus === 'PARSED'
  }
}

Page({
  data: {
    input: '', inputFocus: false, sending: false, resolving: false, loadingHistory: false, deletingConversationId: null,
    run: null, conversationId: null, conversations: [], historyOpen: false, messages: [],
    pendingAttachments: [], uploading: false,
    suggestions: ['查一下电钻库存', '把商品 2 上架', '把商品 2 的实际库存调整为 20，原因是门店盘点']
  },

  onShow() {
    this.loadConversations()
    const conversationId = wx.getStorageSync(ACTIVE_CONVERSATION_KEY)
    if (conversationId && !this.data.conversationId) this.loadConversation(conversationId)
    else if (!conversationId) {
      const runId = wx.getStorageSync(ACTIVE_RUN_KEY)
      if (runId) this.loadRun(runId)
    }
  },

  onPullDownRefresh() {
    const runId = this.data.run && this.data.run.runId
    if (!runId) return wx.stopPullDownRefresh()
    this.loadRun(runId).finally(() => wx.stopPullDownRefresh())
  },

  updateInput(event) { this.setData({ input: event.detail.value }) },
  useSuggestion(event) { this.setData({ input: event.currentTarget.dataset.text || '' }) },
  openExcelWorkflow() { openPage('/pages/excel-upload/index') },
  focusQuestion() { this.setData({ inputFocus: true }) },
  blurQuestion() { this.setData({ inputFocus: false }) },

  chooseAttachment() {
    if (this.data.uploading || this.data.pendingAttachments.length >= 3) return
    wx.showActionSheet({ itemList: ['拍照或选择图片', '选择 Excel / CSV 文件'] }).then((result) => {
      if (result.tapIndex === 0) {
        return wx.chooseMedia({ count: 3 - this.data.pendingAttachments.length, mediaType: ['image'], sourceType: ['album', 'camera'] })
          .then((choice) => this.uploadFiles(choice.tempFiles || []))
      }
      return wx.chooseMessageFile({ count: 3 - this.data.pendingAttachments.length, type: 'file', extension: ['xls', 'xlsx', 'csv'] })
        .then((choice) => this.uploadFiles(choice.tempFiles || []))
    }).catch(() => {})
  },

  uploadFiles(paths) {
    if (!paths.length) return Promise.resolve()
    this.setData({ uploading: true })
    let chain = Promise.resolve()
    paths.forEach((path) => {
      chain = chain.then(() => api.uploadAgentAttachment(path, this.data.conversationId)).then((attachment) => {
        const conversationId = attachment.conversationId || this.data.conversationId
        if (conversationId) wx.setStorageSync(ACTIVE_CONVERSATION_KEY, conversationId)
        this.setData({
          conversationId,
          pendingAttachments: this.data.pendingAttachments.concat(attachmentView(attachment))
        })
      })
    })
    return chain.catch((error) => {
      wx.showToast({ title: errorMessage(error, '附件上传失败'), icon: 'none', duration: 3000 })
    }).finally(() => this.setData({ uploading: false }))
  },

  removeAttachment(event) {
    const id = Number(event.currentTarget.dataset.id)
    if (!id) return
    const attachment = this.data.pendingAttachments.find((item) => Number(item.id) === id)
    api.deleteAgentAttachment(attachment || id).then(() => {
      this.setData({ pendingAttachments: this.data.pendingAttachments.filter((item) => Number(item.id) !== id) })
    }).catch((error) => wx.showToast({ title: errorMessage(error, '附件删除失败'), icon: 'none' }))
  },

  openHistory() { this.setData({ historyOpen: true }) },
  closeHistory() { this.setData({ historyOpen: false }) },

  loadConversations() {
    this.setData({ loadingHistory: true })
    return api.getAgentConversations().then((items) => {
      this.setData({ conversations: (items || []).map((item) => ({
        ...item, timeText: historyTime(item.updateTime), active: item.id === this.data.conversationId
      })) })
    }).catch(() => {}).finally(() => this.setData({ loadingHistory: false }))
  },

  chooseConversation(event) {
    const conversationId = Number(event.currentTarget.dataset.id)
    if (!conversationId) return
    this.setData({ historyOpen: false })
    this.loadConversation(conversationId)
  },

  deleteConversation(event) {
    const conversationId = Number(event.currentTarget.dataset.id)
    if (!conversationId || this.data.deletingConversationId) return
    const conversation = this.data.conversations.find((item) => Number(item.id) === conversationId)
    wx.showModal({
      title: '删除历史会话',
      content: `确定删除“${(conversation && conversation.title) || '这条会话'}”吗？聊天记录删除后无法恢复。`,
      confirmText: '删除',
      confirmColor: '#C4473A'
    }).then((result) => {
      if (!result.confirm) return null
      this.setData({ deletingConversationId: conversationId })
      return api.deleteAgentConversation(conversationId).then(() => {
        const update = {
          conversations: this.data.conversations.filter((item) => Number(item.id) !== conversationId)
        }
        if (Number(this.data.conversationId) === conversationId) {
          wx.removeStorageSync(ACTIVE_RUN_KEY)
          wx.removeStorageSync(ACTIVE_CONVERSATION_KEY)
          Object.assign(update, {
            conversationId: null, run: null, messages: [], input: '', pendingAttachments: []
          })
        }
        this.setData(update)
        wx.showToast({ title: '会话已删除', icon: 'success' })
      }).catch((error) => {
        wx.showToast({ title: errorMessage(error, '会话删除失败'), icon: 'none', duration: 3000 })
      }).finally(() => this.setData({ deletingConversationId: null }))
    }).catch(() => {})
  },

  loadConversation(conversationId) {
    return api.getAgentConversation(conversationId).then((detail) => {
      const source = detail.messages || []
      const waiting = source.slice().reverse().find((message) => message.status === 'WAITING_APPROVAL')
      const messages = source
        .filter((message) => message.role !== 'assistant' || message.status !== 'WAITING_APPROVAL')
        .map(conversationMessage)
      wx.setStorageSync(ACTIVE_CONVERSATION_KEY, detail.id)
      this.setData({ conversationId: detail.id, messages, run: null })
      if (waiting) {
        wx.setStorageSync(ACTIVE_RUN_KEY, waiting.runId)
        return this.loadRun(waiting.runId)
      }
      wx.removeStorageSync(ACTIVE_RUN_KEY)
      return null
    }).catch((error) => {
      wx.removeStorageSync(ACTIVE_CONVERSATION_KEY)
      wx.showToast({ title: errorMessage(error, '会话加载失败'), icon: 'none' })
    })
  },

  send() {
    const content = (this.data.input || '').trim()
    const attachments = this.data.pendingAttachments
    if ((!content && !attachments.length) || this.data.sending || this.data.uploading) return
    if (attachments.some((item) => !item.ready)) {
      wx.showToast({ title: '附件尚未完成识别', icon: 'none' })
      return
    }
    const displayContent = content || '请识别附件中的订单信息并生成销售草稿'
    this.setData({
      input: '', sending: true, pendingAttachments: [],
      messages: this.data.messages.concat({ role: 'user', content: displayContent, attachments })
    })
    api.sendAgentMessage(displayContent, this.data.conversationId, attachments.map((item) => item.id)).then((result) => {
      this.applyRun(result, true)
      return this.loadConversations()
    }).catch((error) => {
      const content = errorMessage(error, '经营助手暂时无法处理，请稍后重试')
      this.setData({ pendingAttachments: attachments })
      this.setData({ messages: this.data.messages.concat({
        role: 'error', content, blocks: formatAgentContent(content)
      }) })
    }).finally(() => this.setData({ sending: false }))
  },

  loadRun(runId) {
    return api.getAgentRun(runId).then((result) => this.applyRun(result, false)).catch(() => {
      wx.removeStorageSync(ACTIVE_RUN_KEY)
      this.setData({ run: null })
    })
  },

  applyRun(result, appendMessage) {
    const run = normalizeResult(result)
    if (!run) return
    if (run.status === 'WAITING_APPROVAL') wx.setStorageSync(ACTIVE_RUN_KEY, run.runId)
    else wx.removeStorageSync(ACTIVE_RUN_KEY)
    const update = { run, conversationId: run.conversationId || this.data.conversationId }
    if (run.conversationId) wx.setStorageSync(ACTIVE_CONVERSATION_KEY, run.conversationId)
    if (appendMessage && run.output && run.status !== 'WAITING_APPROVAL') {
      update.messages = this.data.messages.concat(assistantMessage(run.output, run.status))
    }
    this.setData(update)
  },

  approve() { this.resolveApproval(true) },
  reject() { this.resolveApproval(false) },

  resolveApproval(approved) {
    const run = this.data.run
    if (!run || this.data.resolving) return
    this.setData({ resolving: true })
    api.resolveAgentApproval(run.runId, approved).then((result) => {
      this.applyRun(result, true)
      this.loadConversations()
      wx.showToast({ title: approved ? '操作已确认' : '操作已取消', icon: 'none' })
    }).catch((error) => {
      wx.showToast({ title: errorMessage(error, '确认处理失败'), icon: 'none' })
      return this.loadRun(run.runId)
    }).finally(() => this.setData({ resolving: false }))
  },

  startNew() {
    wx.removeStorageSync(ACTIVE_RUN_KEY)
    wx.removeStorageSync(ACTIVE_CONVERSATION_KEY)
    this.setData({ run: null, conversationId: null, messages: [], input: '', historyOpen: false, pendingAttachments: [] })
  }
})
