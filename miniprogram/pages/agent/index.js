const api = require('../../utils/api')
const { formatAgentContent } = require('../../utils/agent-format')
const { openPage } = require('../../utils/router')

const ACTIVE_RUN_KEY = 'agent_active_run_id'
const ACTIVE_CONVERSATION_KEY = 'agent_active_conversation_id'
const STREAM_POLL_INTERVAL = 320
const MAX_STREAM_POLL_FAILURES = 4

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
      PARTIAL: '部分完成', CANCELLED: '已取消', EXPIRED: '已过期', RUNNING: '处理中'
    }[result.status] || result.status,
    statusTone: result.status === 'COMPLETED' ? 'success' : result.status === 'FAILED' ? 'danger' : result.status === 'PARTIAL' ? 'warning' : result.status === 'CANCELLED' ? 'neutral' : 'info',
    stageText: (result.progress && result.progress.stageText) || result.stageText || (result.output ? '正在生成答复' : '正在理解要求并查询业务数据'),
    progressPercent: Number((result.progress && result.progress.percent) || result.progressPercent || 0),
    progressCompleted: Number((result.progress && result.progress.completed) || 0),
    progressTotal: Number((result.progress && result.progress.total) || 0),
    issueCount: Number((result.progress && result.progress.issueCount) || 0),
    canCancel: result.progress ? result.progress.canCancel !== false : result.status === 'RUNNING',
    canLeave: result.progress ? result.progress.canLeave !== false : true,
    summary: result.summary || null,
    actions: result.actions || [],
    retry: result.retry || null,
    pendingAction: action ? {
      ...action,
      isHighRisk: action.risk === 'R2_WRITE',
      preview: preview ? { ...preview, items: preview.items || [] } : { items: [] },
      impact: action.impact || null
    } : null
  }
}

function assistantMessage(content, status, streaming = false) {
  return {
    role: status === 'FAILED' ? 'error' : 'assistant',
    content: content || '',
    blocks: streaming ? [] : formatAgentContent(content || ''),
    streaming,
    statusText: streaming ? '正在生成' : ''
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
    input: '', inputFocus: false, sending: false, streaming: false, resolving: false, loadingHistory: false,
    deletingConversationId: null, renamingConversationId: null, cancelling: false,
    run: null, conversationId: null, currentTitle: '新对话', conversations: [], historyOpen: false, messages: [],
    pendingAttachments: [], uploading: false, connectionInterrupted: false, lastFailedInput: '',
    scrollTarget: 'conversation-end',
    taskEntries: [
      { title: '处理 Excel', desc: '识别询价、报价和价格表', mark: '表', kind: 'excel', tone: 'blue' },
      { title: '创建销售草稿', desc: '查客户、商品和库存后生成草稿', mark: '单', prompt: '帮我创建一张销售草稿', tone: 'green' },
      { title: '新增商品', desc: '填写商品资料并建立初始库存', mark: '商', prompt: '帮我新增一个商品', tone: 'amber' },
      { title: '盘点库存', desc: '查询商品后调整为实际数量', mark: '库', prompt: '帮我盘点并调整一个商品的库存', tone: 'purple' },
      { title: '商品上下架', desc: '搜索商品并确认修改状态', mark: '架', prompt: '帮我修改一个商品的上下架状态', tone: 'blue' },
      { title: '其他问题', desc: '直接说你想了解什么', mark: '问', kind: 'question', tone: 'neutral' }
    ],
    suggestions: ['查一下电钻还有多少库存', '给老王开两把东成电钻，先记账', '把商品 2 上架', '把商品 2 的实际库存调整为 20，原因是门店盘点']
  },

  onShow() {
    this.loadConversations()
    const conversationId = wx.getStorageSync(ACTIVE_CONVERSATION_KEY)
    if (conversationId && !this.data.conversationId) {
      this.loadConversation(conversationId)
      return
    }
    const runId = wx.getStorageSync(ACTIVE_RUN_KEY)
    if (runId && !this.pollTimer) this.loadRun(runId, true)
  },

  onHide() { this.stopPolling() },
  onUnload() { this.stopPolling() },

  onPullDownRefresh() {
    const runId = this.data.run && this.data.run.runId
    if (!runId) return wx.stopPullDownRefresh()
    this.loadRun(runId, true).finally(() => wx.stopPullDownRefresh())
  },

  updateInput(event) { this.setData({ input: event.detail.value }) },
  useSuggestion(event) { this.setData({ input: event.currentTarget.dataset.text || '' }) },
  startTask(event) {
    const kind = event.currentTarget.dataset.kind
    if (kind === 'excel') return this.openExcelWorkflow()
    this.setData({ input: event.currentTarget.dataset.prompt || '', inputFocus: true })
  },
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

  openHistory() {
    this.setData({ historyOpen: true })
    this.loadConversations()
  },
  closeHistory() { this.setData({ historyOpen: false }) },
  preventClose() {},

  loadConversations() {
    this.setData({ loadingHistory: true })
    return api.getAgentConversations().then((items) => {
      const conversations = (items || []).map((item) => ({
        ...item, timeText: historyTime(item.updateTime), active: Number(item.id) === Number(this.data.conversationId)
      }))
      const active = conversations.find((item) => item.active)
      this.setData({ conversations, currentTitle: active ? active.title : this.data.currentTitle })
    }).catch(() => {}).finally(() => this.setData({ loadingHistory: false }))
  },

  chooseConversation(event) {
    const conversationId = Number(event.currentTarget.dataset.id)
    if (!conversationId) return
    this.stopPolling()
    this.setData({ historyOpen: false, sending: false, streaming: false })
    this.loadConversation(conversationId)
  },

  renameConversation(event) {
    const conversationId = Number(event.currentTarget.dataset.id)
    if (!conversationId || this.data.renamingConversationId) return
    const conversation = this.data.conversations.find((item) => Number(item.id) === conversationId)
    if (!conversation) return
    wx.showModal({
      title: '修改会话标题',
      content: conversation.title || '',
      editable: true,
      placeholderText: '输入会话标题',
      confirmText: '保存'
    }).then((result) => {
      const title = (result.content || '').trim()
      if (!result.confirm || !title || title === conversation.title) return null
      this.setData({ renamingConversationId: conversationId })
      return api.renameAgentConversation(conversationId, title).then(() => {
        const conversations = this.data.conversations.map((item) => Number(item.id) === conversationId ? { ...item, title } : item)
        this.setData({
          conversations,
          currentTitle: Number(this.data.conversationId) === conversationId ? title : this.data.currentTitle
        })
        wx.showToast({ title: '标题已更新', icon: 'success' })
      }).catch((error) => wx.showToast({ title: errorMessage(error, '标题修改失败'), icon: 'none' }))
        .finally(() => this.setData({ renamingConversationId: null }))
    }).catch(() => {})
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
          this.stopPolling()
          wx.removeStorageSync(ACTIVE_RUN_KEY)
          wx.removeStorageSync(ACTIVE_CONVERSATION_KEY)
          Object.assign(update, {
            conversationId: null, currentTitle: '新对话', run: null, messages: [], input: '',
            sending: false, streaming: false, pendingAttachments: []
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
      const active = source.slice().reverse().find((message) => message.status === 'RUNNING' || message.status === 'WAITING_APPROVAL')
      const messages = source
        .filter((message) => !(message.role === 'assistant' && active && message.runId === active.runId))
        .map(conversationMessage)
      wx.setStorageSync(ACTIVE_CONVERSATION_KEY, detail.id)
      this.setData({
        conversationId: detail.id,
        currentTitle: detail.title || '新对话',
        messages,
        run: null,
        sending: Boolean(active),
        streaming: Boolean(active && active.status === 'RUNNING')
      }, () => this.scrollToBottom())
      if (active) {
        wx.setStorageSync(ACTIVE_RUN_KEY, active.runId)
        if (active.status === 'RUNNING') this.ensureStreamingMessage()
        return this.loadRun(active.runId, active.status === 'RUNNING')
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
      input: '', sending: true, streaming: true, pendingAttachments: [], connectionInterrupted: false,
      messages: this.data.messages.concat(
        { role: 'user', content: displayContent, attachments },
        assistantMessage('', 'RUNNING', true)
      )
    }, () => this.scrollToBottom())
    api.startAgentMessageStream(displayContent, this.data.conversationId, attachments.map((item) => item.id)).then((result) => {
      const run = normalizeResult(result)
      if (!run) throw new Error('经营助手未返回任务信息')
      const conversationId = run.conversationId || this.data.conversationId
      wx.setStorageSync(ACTIVE_RUN_KEY, run.runId)
      if (conversationId) wx.setStorageSync(ACTIVE_CONVERSATION_KEY, conversationId)
      this.setData({ run, conversationId })
      this.loadConversations()
      this.startPolling(run.runId, 100)
    }).catch((error) => {
      const content = errorMessage(error, '经营助手暂时无法处理，请稍后重试')
      const messages = this.data.messages.slice(0, -1).concat(assistantMessage(content, 'FAILED'))
      this.setData({ sending: false, streaming: false, pendingAttachments: attachments, lastFailedInput: displayContent, messages }, () => this.scrollToBottom())
    })
  },

  loadRun(runId, resumePolling = false) {
    return api.getAgentRun(runId).then((result) => {
      this.syncRun(result)
      if (resumePolling && result && result.status === 'RUNNING') this.startPolling(runId)
      return result
    }).catch(() => {
      if (resumePolling) this.handlePollFailure(runId)
      else {
        wx.removeStorageSync(ACTIVE_RUN_KEY)
        this.setData({ run: null, sending: false, streaming: false })
      }
    })
  },

  startPolling(runId, delay = STREAM_POLL_INTERVAL) {
    this.stopPolling()
    this.streamPollFailures = 0
    this.pollTimer = setTimeout(() => this.pollRun(runId), delay)
  },

  pollRun(runId) {
    this.pollTimer = null
    api.getAgentRun(runId).then((result) => {
      this.streamPollFailures = 0
      this.syncRun(result)
      if (result && result.status === 'RUNNING') {
        this.pollTimer = setTimeout(() => this.pollRun(runId), STREAM_POLL_INTERVAL)
      }
    }).catch(() => this.handlePollFailure(runId))
  },

  handlePollFailure(runId) {
    this.streamPollFailures = (this.streamPollFailures || 0) + 1
    if (this.streamPollFailures <= MAX_STREAM_POLL_FAILURES) {
      this.pollTimer = setTimeout(() => this.pollRun(runId), STREAM_POLL_INTERVAL * this.streamPollFailures)
      return
    }
    this.stopPolling()
    this.setData({ sending: false, streaming: false, connectionInterrupted: true })
    wx.showToast({ title: '连接中断，稍后可从会话中继续查看', icon: 'none', duration: 3000 })
  },

  stopPolling() {
    if (this.pollTimer) clearTimeout(this.pollTimer)
    this.pollTimer = null
  },

  retryConnection() {
    const runId = this.data.run && this.data.run.runId
    if (!runId) return wx.showToast({ title: '没有可继续的任务', icon: 'none' })
    this.setData({ connectionInterrupted: false, sending: true })
    this.loadRun(runId, true)
  },

  cancelRun() {
    const run = this.data.run
    if (!run || run.status !== 'RUNNING' || this.data.cancelling) return
    wx.showModal({
      title: '取消这次任务？',
      content: '已经完成的查询结果会保留，尚未执行的操作将停止。',
      confirmText: '取消任务',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ cancelling: true })
        api.cancelAgentRun(run.runId).then((cancelled) => {
          this.syncRun(cancelled)
          wx.showToast({ title: '任务已取消', icon: 'none' })
        }).catch((error) => wx.showToast({ title: errorMessage(error, '任务没有取消成功'), icon: 'none' }))
          .finally(() => this.setData({ cancelling: false }))
      }
    })
  },

  retryLastMessage() { this.setData({ input: this.data.lastFailedInput || '', inputFocus: true }) },
  openProduct(event) {
    const id = event.currentTarget.dataset.id
    if (id) openPage('/pages/product-detail/index', { id })
  },
  openWorkbench() { openPage('/pages/workbench/index') },
  openFileTasks() { openPage('/pages/excel-tasks/index') },
  openResultAction(event) {
    const target = event.currentTarget.dataset.target
    const params = event.currentTarget.dataset.params || {}
    const routes = {
      WORKBENCH: '/pages/workbench/index', PRODUCT_LIST: '/pages/products/index', PRODUCT_DETAIL: '/pages/product-detail/index',
      INVENTORY_LIST: '/pages/inventories/index', SALES_ORDER_DETAIL: '/pages/sales-detail/index',
      PURCHASE_ORDER_DETAIL: '/pages/purchase-detail/index', EXCEL_TASK_DETAIL: '/pages/excel-task-detail/index',
      PRICE_HISTORY: '/pages/price-history/index'
    }
    const path = routes[target]
    if (!path) return wx.showToast({ title: '暂时无法打开这个结果', icon: 'none' })
    openPage(path, params)
  },

  syncRun(result) {
    const run = normalizeResult(result)
    if (!run) return
    const conversationId = run.conversationId || this.data.conversationId
    if (conversationId) wx.setStorageSync(ACTIVE_CONVERSATION_KEY, conversationId)
    if (run.status === 'RUNNING') {
      wx.setStorageSync(ACTIVE_RUN_KEY, run.runId)
      this.updateStreamingMessage(run.output || '')
      this.setData({ run, conversationId, sending: true, streaming: true })
      return
    }
    this.stopPolling()
    if (run.status === 'WAITING_APPROVAL') {
      wx.setStorageSync(ACTIVE_RUN_KEY, run.runId)
      this.removeStreamingMessage()
      this.setData({ run, conversationId, sending: false, streaming: false }, () => this.scrollToBottom())
      this.loadConversations()
      return
    }
    wx.removeStorageSync(ACTIVE_RUN_KEY)
    this.finalizeStreamingMessage(run.output, run.status)
    this.setData({ run, conversationId, sending: false, streaming: false }, () => this.scrollToBottom())
    this.loadConversations()
  },

  ensureStreamingMessage() {
    const messages = this.data.messages.slice()
    if (!messages.length || !messages[messages.length - 1].streaming) {
      messages.push(assistantMessage('', 'RUNNING', true))
      this.setData({ messages })
    }
  },

  updateStreamingMessage(content) {
    this.ensureStreamingMessage()
    const messages = this.data.messages.slice()
    const index = messages.map((item) => item.streaming).lastIndexOf(true)
    if (index < 0) return
    messages[index] = assistantMessage(content, 'RUNNING', true)
    this.setData({ messages }, () => this.scrollToBottom())
  },

  removeStreamingMessage() {
    this.setData({ messages: this.data.messages.filter((item) => !item.streaming) })
  },

  finalizeStreamingMessage(content, status) {
    const messages = this.data.messages.slice()
    const index = messages.map((item) => item.streaming).lastIndexOf(true)
    const finalMessage = assistantMessage(content || '任务已结束', status)
    if (index >= 0) messages[index] = finalMessage
    else if (content) messages.push(finalMessage)
    this.setData({ messages })
  },

  scrollToBottom() {
    this.setData({ scrollTarget: '' }, () => this.setData({ scrollTarget: 'conversation-end' }))
  },

  approve() { this.resolveApproval(true) },
  reject() { this.resolveApproval(false) },

  resolveApproval(approved) {
    const run = this.data.run
    if (!run || this.data.resolving) return
    this.setData({ resolving: true })
    api.resolveAgentApproval(run.runId, approved).then((result) => {
      this.syncRun(result)
      wx.showToast({ title: approved ? '操作已确认' : '操作已取消', icon: 'none' })
    }).catch((error) => {
      wx.showToast({ title: errorMessage(error, '确认处理失败'), icon: 'none' })
      return this.loadRun(run.runId)
    }).finally(() => this.setData({ resolving: false }))
  },

  startNew() {
    this.stopPolling()
    wx.removeStorageSync(ACTIVE_RUN_KEY)
    wx.removeStorageSync(ACTIVE_CONVERSATION_KEY)
    this.setData({
      run: null, conversationId: null, currentTitle: '新对话', messages: [], input: '', historyOpen: false,
      sending: false, streaming: false, pendingAttachments: []
    })
  }
})
