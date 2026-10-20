const api = require('../../utils/api')
const { formatAgentContent } = require('../../utils/agent-format')
const { fileView, openAgentFile, confirmDeleteAgentFile } = require('../../utils/agent-files')
const { openPage } = require('../../utils/router')

const ACTIVE_RUN_KEY = 'agent_active_run_id'
const ACTIVE_CONVERSATION_KEY = 'agent_active_conversation_id'
const PENDING_PROMPT_KEY = 'agent_pending_prompt'
const DRAWER_STATE_KEY = 'agent_drawer_sections'
const STREAM_POLL_INTERVAL = 320
const MAX_STREAM_POLL_FAILURES = 4
const DRAWER_ROUTES = Object.freeze({
  files: '/pages/agent-files/index',
  sales: '/pages/sales-orders/index',
  purchases: '/pages/purchase-orders/index',
  inventory: '/pages/inventories/index',
  finance: '/pages/finance-ledger/index',
  customers: '/pages/customers/index',
  suppliers: '/pages/suppliers/index',
  factories: '/pages/factory-ledgers/index',
  products: '/pages/products/index',
  prices: '/pages/price-history/index',
  mappings: '/pages/product-mappings/index',
  excel: '/pages/excel-tasks/index',
  reports: '/pages/report-center/index',
  settings: '/pages/settings/index'
})

function errorMessage(error, fallback) {
  return (error && (error.message || error.errMsg)) || fallback
}

function workbookPreview(preview) {
  if (!preview || preview.kind !== 'EXCEL_WORKBOOK') return preview
  return {
    ...preview,
    sheets: (preview.sheets || []).map((sheet) => ({
      ...sheet,
      columnsText: (sheet.columns || []).join(' · '),
      sampleLines: (sheet.sampleRows || []).map((row) => (row || []).join(' · '))
    }))
  }
}

function money(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : '待填写'
}

function billingPreview(preview) {
  if (!preview || preview.kind !== 'BILLING_DRAFT') return preview
  const content = preview.content || {}
  const lines = (content.lines || []).map((line, index) => {
    const quantity = line.quantity === null || line.quantity === undefined ? '' : line.quantity
    const price = line.price === null || line.price === undefined ? '' : line.price
    const amount = quantity !== '' && price !== '' ? Number(quantity) * Number(price) : null
    return {
      ...line,
      index: index + 1,
      quantityText: quantity === '' ? '待填' : quantity,
      priceText: price === '' ? '待填' : money(price),
      amountText: amount === null || !Number.isFinite(amount) ? '待核对' : money(amount),
      meta: [line.specification, line.unit].filter(Boolean).join(' · ') || '规格、单位待补充'
    }
  })
  const readyTotal = lines.reduce((sum, line) => {
    const amount = Number(line.amountText)
    return Number.isFinite(amount) ? sum + amount : sum
  }, 0)
  const incompleteCount = lines.filter((line) => !line.name || !line.unit || line.quantityText === '待填' || line.priceText === '待填').length
  return {
    ...preview,
    content,
    billingLines: lines,
    billingTotal: money(readyTotal),
    billingIncompleteCount: incompleteCount,
    billingTitle: content.customerName ? `${content.customerName}的销售单` : '销售单草稿'
  }
}

function billingDocumentId(value) {
  const output = String((value && value.output) || value || '')
  if (!/BILLING_DOCUMENT/.test(output)) return null
  const matched = output.match(/["']documentId["']\s*:\s*(\d+)/)
  return matched ? Number(matched[1]) : null
}

function billingDocumentAction(result) {
  const id = billingDocumentId(result)
  return id ? { label: '编辑并预览单据', target: 'BILLING_DOCUMENT', params: { id } } : null
}

function normalizeResult(result) {
  if (!result) return null
  const action = result.pendingAction
  const preview = action && action.preview ? billingPreview(workbookPreview(action.preview)) : null
  const resultActions = (result.actions || []).slice()
  const billingAction = billingDocumentAction(result)
  if (billingAction && !resultActions.some((item) => item.target === 'BILLING_DOCUMENT')) resultActions.unshift(billingAction)
  const displayOutput = billingAction
    ? '开单草稿已建立。可以修改商品、数量、单位和价格，预览无误后再保存并导出 Excel。'
    : result.output
  return {
    ...result,
    output: displayOutput,
    billingDocumentId: billingAction ? billingAction.params.id : null,
    files: (result.files || []).map(fileView),
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
    actions: resultActions,
    retry: result.retry || null,
    pendingAction: action ? {
      ...action,
      isHighRisk: action.risk === 'R2_WRITE',
      preview: preview ? { ...preview, items: preview.items || [] } : { items: [] },
      impact: action.impact || null
    } : null
  }
}

function assistantMessage(content, status, streaming = false, files = []) {
  const documentId = billingDocumentId(content)
  const displayContent = documentId
    ? '开单草稿已建立。可以继续修改并预览，确认无误后再保存和导出 Excel。'
    : (content || '')
  return {
    role: status === 'FAILED' ? 'error' : 'assistant',
    content: displayContent,
    blocks: formatAgentContent(displayContent),
    billingDocumentId: documentId,
    streaming,
    statusText: streaming ? '正在生成' : '',
    files: files || []
  }
}

function historyTime(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

function filterConversations(items, query) {
  const keyword = String(query || '').trim().toLowerCase()
  if (!keyword) return items
  return items.filter((item) => `${item.title || ''} ${item.preview || ''}`.toLowerCase().includes(keyword))
}

function conversationTitle(item) {
  const title = String((item && item.title) || '').trim()
  if (!/^附件[:：]/.test(title)) return title || '新对话'
  const preview = String((item && item.preview) || '')
  if (/(开票|单据|商品明细|销售草稿|订单)/.test(preview)) return '识别并整理单据'
  if (/(Excel|表格|文件)/i.test(preview)) return '处理文件'
  return '识别附件内容'
}

function conversationMessage(message) {
  if (message.role === 'assistant') {
    return assistantMessage(message.content, message.status, false, (message.files || []).map(fileView))
  }
  return { role: 'user', content: message.content, attachments: (message.attachments || []).map(attachmentView) }
}

function fileSize(value) {
  const bytes = Number(value) || 0
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function fileExtension(name) {
  const matched = String(name || '').toLowerCase().match(/\.([a-z0-9]+)$/)
  return matched ? matched[1] : ''
}

function explicitlyRequestsImageSaving(content) {
  const text = String(content || '')
  if (/(不要|不必|无需|别)(保存|上传|存到)/.test(text)) return false
  const mentionsImage = /(图片|照片|原图|这张图|这张照片)/.test(text)
  const asksToSave = /(保存|存到|存入|上传|放到|放进|归档)/.test(text)
  const namesFileDestination = /(我的文件|文件列表|文件中心|文件夹|文件里)/.test(text)
  const explicitlyDirectUpload = /直接上传/.test(text)
  const explicitlySaveImage = /保存.{0,4}(图片|照片|原图|这张图)/.test(text)
    || /(图片|照片|原图|这张图).{0,6}保存/.test(text)
  return mentionsImage && asksToSave && (namesFileDestination || explicitlyDirectUpload || explicitlySaveImage)
}

function isImageAttachment(item) {
  return /^image\//i.test(String((item && item.mimeType) || '')) || /\.(jpe?g|png|webp)$/i.test(String((item && item.originalName) || ''))
}

function attachmentView(item) {
  const name = item.originalName || item.name || '附件'
  const mimeType = item.mimeType || ''
  const extension = fileExtension(name)
  const isImage = /^image\//.test(mimeType) || ['jpg', 'jpeg', 'png', 'webp'].includes(extension)
  const isPdf = mimeType === 'application/pdf' || extension === 'pdf'
  const isWord = /word/.test(mimeType) || ['doc', 'docx'].includes(extension)
  const typeText = isImage ? '图片' : isPdf ? 'PDF' : isWord ? 'Word' : ['xls', 'xlsx', 'csv'].includes(extension) ? '表格' : '文件'
  const parseStatus = item.parseStatus || ''
  return {
    ...item,
    originalName: name,
    sizeText: fileSize(item.fileSize || item.size),
    typeText,
    typeMark: isImage ? '图' : isPdf ? 'PDF' : isWord ? '文' : typeText === '表格' ? '表' : '件',
    ready: parseStatus === 'PARSED',
    parseText: parseStatus === 'PARSED' ? '已读取' : (item.errorMessage || (parseStatus === 'FAILED' ? '读取失败' : '等待读取'))
  }
}

Page({
  data: {
    input: '', inputFocus: false, sending: false, streaming: false, resolving: false, loadingHistory: false,
    deletingConversationId: null, renamingConversationId: null, cancelling: false,
    run: null, conversationId: null, currentTitle: '小五', allConversations: [], conversations: [], historyQuery: '', historyOpen: false, messages: [],
    pendingAttachments: [], uploading: false, savingAttachmentId: null, attachmentMenuOpen: false, connectionInterrupted: false, lastFailedInput: '',
    scrollTarget: 'conversation-end', openingFileId: '', deletingFileId: '',
    statusBarHeight: 24, navigationHeight: 68, capsuleReserve: 96,
    businessExpanded: false, contactsExpanded: false, catalogExpanded: false, filesExpanded: false, settingsExpanded: false,
    coreActions: [
      { key: 'billing', title: '快捷开单', description: '录商品并预览销售单' },
      { key: 'scan', title: '拍单识别', description: '拍照生成可修改草稿' },
      { key: 'inventory', title: '查商品库存', description: '查价格、规格和数量' },
      { key: 'ledger', title: '查客户往来', description: '查欠款、收款和历史' }
    ]
  },

  onLoad() {
    const windowInfo = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    const capsule = wx.getMenuButtonBoundingClientRect ? wx.getMenuButtonBoundingClientRect() : null
    const statusBarHeight = Number(windowInfo.statusBarHeight || 24)
    const barHeight = capsule ? Math.max(44, capsule.height + Math.max(6, capsule.top - statusBarHeight) * 2) : 44
    const capsuleReserve = capsule ? Math.max(88, windowInfo.windowWidth - capsule.left + 8) : 96
    const savedSections = wx.getStorageSync(DRAWER_STATE_KEY) || {}
    const sectionState = {}
    ;['businessExpanded', 'contactsExpanded', 'catalogExpanded', 'filesExpanded', 'settingsExpanded'].forEach((key) => {
      if (typeof savedSections[key] === 'boolean') sectionState[key] = savedSections[key]
    })
    this.setData({ statusBarHeight, navigationHeight: statusBarHeight + barHeight, capsuleReserve, ...sectionState })
  },

  onShow() {
    this.consumePendingPrompt()
    this.loadConversations()
    api.cleanupTerminalTemporaryAgentFiles().catch(() => {})
    const conversationId = wx.getStorageSync(ACTIVE_CONVERSATION_KEY)
    if (conversationId && !this.data.conversationId) {
      this.loadConversation(conversationId)
      return
    }
    const runId = wx.getStorageSync(ACTIVE_RUN_KEY)
    if (runId && !this.pollTimer) this.loadRun(runId, true)
  },

  onHide() { this.stopPolling(); this.closeAttachmentMenu() },
  onUnload() { this.stopPolling() },

  onPullDownRefresh() {
    const runId = this.data.run && this.data.run.runId
    if (!runId) return wx.stopPullDownRefresh()
    this.loadRun(runId, true).finally(() => wx.stopPullDownRefresh())
  },

  updateInput(event) { this.setData({ input: event.detail.value }) },
  useSuggestion(event) { this.setData({ input: event.currentTarget.dataset.text || '', inputFocus: true }) },
  blurQuestion() { this.setData({ inputFocus: false }) },

  useCoreAction(event) {
    const action = event.currentTarget.dataset.action
    this.setData({ historyOpen: false, attachmentMenuOpen: false })
    if (action === 'scan') return this.chooseCamera()
    if (action === 'billing') return openPage('/pages/billing-editor/index')
    const prompts = {
      billing: '我要快捷开单：',
      inventory: '查商品库存：',
      ledger: '查客户往来：'
    }
    const input = prompts[action]
    if (input) this.setData({ input, inputFocus: true }, () => this.scrollToBottom())
  },

  toggleDrawerSection(event) {
    const key = event.currentTarget.dataset.section
    const allowed = ['businessExpanded', 'contactsExpanded', 'catalogExpanded', 'filesExpanded', 'settingsExpanded']
    if (!allowed.includes(key)) return
    const next = !this.data[key]
    this.setData({ [key]: next })
    const saved = wx.getStorageSync(DRAWER_STATE_KEY) || {}
    wx.setStorageSync(DRAWER_STATE_KEY, { ...saved, [key]: next })
  },

  openDrawerPage(event) {
    const path = DRAWER_ROUTES[event.currentTarget.dataset.route]
    if (!path) return
    this.setData({ historyOpen: false })
    openPage(path)
  },

  // The files page hands over a prompt such as “请修改文件 #7「…」：” through storage,
  // because the agent page is a tab page and cannot receive URL parameters.
  consumePendingPrompt() {
    const pending = wx.getStorageSync(PENDING_PROMPT_KEY)
    if (!pending) return
    wx.removeStorageSync(PENDING_PROMPT_KEY)
    this.setData({ input: pending, inputFocus: true })
  },

  modifyGeneratedFile(event) {
    const file = this.findGeneratedFile(Number(event.currentTarget.dataset.id))
    if (!file) return
    this.setData({ input: `请修改文件 #${file.id}「${file.name}」：`, inputFocus: true }, () => this.scrollToBottom())
  },
  quickEditGeneratedFile(event) {
    const id = Number(event.currentTarget.dataset.id)
    if (id) openPage('/pages/workbook-editor/index', { id })
  },

  toggleAttachmentMenu() {
    if (this.data.sending || this.data.resolving || this.data.uploading) return
    if (this.data.pendingAttachments.length >= 3) return wx.showToast({ title: '一次最多添加 3 个附件', icon: 'none' })
    this.setData({ attachmentMenuOpen: !this.data.attachmentMenuOpen, inputFocus: false })
  },
  closeAttachmentMenu() { this.setData({ attachmentMenuOpen: false }) },
  chooseCamera() { this.chooseMedia(['camera']) },
  chooseAlbum() { this.chooseMedia(['album']) },
  chooseMedia(sourceType) {
    const count = 3 - this.data.pendingAttachments.length
    this.closeAttachmentMenu()
    return wx.chooseMedia({ count, mediaType: ['image'], sourceType })
      .then((choice) => this.uploadFiles(choice.tempFiles || []))
      .catch(() => {})
  },
  chooseDocument() {
    const count = 3 - this.data.pendingAttachments.length
    this.closeAttachmentMenu()
    return wx.chooseMessageFile({ count, type: 'file', extension: ['xls', 'xlsx', 'csv', 'doc', 'docx', 'pdf'] })
      .then((choice) => this.uploadFiles(choice.tempFiles || []))
      .catch(() => {})
  },
  chooseGeneratedExcel() {
    this.closeAttachmentMenu()
    this.openMyFiles()
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

  savePendingAttachment(event) {
    const id = Number(event.currentTarget.dataset.id)
    const attachment = this.data.pendingAttachments.find((item) => Number(item.id) === id)
    if (!attachment || !isImageAttachment(attachment) || this.data.savingAttachmentId) return
    if (attachment.savedToFiles) return wx.showToast({ title: '图片已保存到我的文件', icon: 'none' })
    this.setData({ savingAttachmentId: id })
    api.organizeFile('ATTACHMENT', id, {
      purposeCode: 'UNCLASSIFIED', relatedType: 'NONE', manualUpload: true
    }).then(() => {
      this.setData({
        pendingAttachments: this.data.pendingAttachments.map((item) => Number(item.id) === id ? { ...item, savedToFiles: true } : item)
      })
      wx.showToast({ title: '原图已保存到我的文件', icon: 'success' })
    }).catch((error) => {
      wx.showToast({ title: errorMessage(error, '保存失败，请重试'), icon: 'none', duration: 3000 })
    }).finally(() => this.setData({ savingAttachmentId: null }))
  },

  openHistory() {
    this.setData({ historyOpen: true })
    this.loadConversations()
  },
  closeHistory() { this.setData({ historyOpen: false }) },
  preventClose() {},

  searchHistory(event) {
    const historyQuery = event.detail.value || ''
    this.setData({
      historyQuery,
      conversations: filterConversations(this.data.allConversations, historyQuery)
    })
  },

  clearHistorySearch() {
    this.setData({ historyQuery: '', conversations: this.data.allConversations })
  },

  loadConversations() {
    this.setData({ loadingHistory: true })
    return api.getAgentConversations().then((items) => {
      const allConversations = (items || []).map((item) => ({
        ...item, title: conversationTitle(item), timeText: historyTime(item.updateTime), active: Number(item.id) === Number(this.data.conversationId)
      }))
      const conversations = filterConversations(allConversations, this.data.historyQuery)
      const active = allConversations.find((item) => item.active)
      this.setData({ allConversations, conversations, currentTitle: active ? active.title : this.data.currentTitle })
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
    const conversation = this.data.allConversations.find((item) => Number(item.id) === conversationId)
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
        const allConversations = this.data.allConversations.map((item) => Number(item.id) === conversationId ? { ...item, title } : item)
        this.setData({
          allConversations,
          conversations: filterConversations(allConversations, this.data.historyQuery),
          currentTitle: Number(this.data.conversationId) === conversationId ? title : this.data.currentTitle
        })
        wx.showToast({ title: '标题已更新', icon: 'success' })
      }).catch((error) => wx.showToast({ title: errorMessage(error, '标题修改失败'), icon: 'none' }))
        .finally(() => this.setData({ renamingConversationId: null }))
    }).catch(() => {})
  },

  conversationActions(event) {
    const id = Number(event.currentTarget.dataset.id)
    if (!id) return
    wx.showActionSheet({ itemList: ['修改标题', '删除会话'] }).then((result) => {
      const wrapped = { currentTarget: { dataset: { id } } }
      if (result.tapIndex === 0) this.renameConversation(wrapped)
      if (result.tapIndex === 1) this.deleteConversation(wrapped)
    }).catch(() => {})
  },

  deleteConversation(event) {
    const conversationId = Number(event.currentTarget.dataset.id)
    if (!conversationId || this.data.deletingConversationId) return
    const conversation = this.data.allConversations.find((item) => Number(item.id) === conversationId)
    wx.showModal({
      title: '删除历史会话',
      content: `确定删除“${(conversation && conversation.title) || '这条会话'}”吗？聊天记录删除后无法恢复。`,
      confirmText: '删除',
      confirmColor: '#C4473A'
    }).then((result) => {
      if (!result.confirm) return null
      this.setData({ deletingConversationId: conversationId })
      return api.deleteAgentConversation(conversationId).then(() => {
        const allConversations = this.data.allConversations.filter((item) => Number(item.id) !== conversationId)
        const update = { allConversations, conversations: filterConversations(allConversations, this.data.historyQuery) }
        if (Number(this.data.conversationId) === conversationId) {
          this.stopPolling()
          wx.removeStorageSync(ACTIVE_RUN_KEY)
          wx.removeStorageSync(ACTIVE_CONVERSATION_KEY)
          Object.assign(update, {
            conversationId: null, currentTitle: '小五', run: null, messages: [], input: '',
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
        currentTitle: conversationTitle({ title: detail.title }),
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
    if ((!content && !attachments.length) || this.data.sending || this.data.resolving || this.data.uploading || this.data.savingAttachmentId
      || (this.data.run && this.data.run.status === 'WAITING_APPROVAL')) return
    if (attachments.some((item) => !item.ready)) {
      wx.showToast({ title: '附件尚未完成识别', icon: 'none' })
      return
    }
    const displayContent = content || '请阅读这些附件，先概括主要内容并告诉我可以继续处理什么；不要写入业务数据。'
    const saveImages = attachments.filter((item) => isImageAttachment(item) && (item.savedToFiles || explicitlyRequestsImageSaving(displayContent)))
    const savedIds = new Set(saveImages.map((item) => Number(item.id)))
    const transientAttachments = attachments.filter((item) => !savedIds.has(Number(item.id)))
    this.setData({
      input: '', sending: true, streaming: true, pendingAttachments: [], attachmentMenuOpen: false, connectionInterrupted: false,
      messages: this.data.messages.concat(
        { role: 'user', content: displayContent, attachments },
        assistantMessage('', 'RUNNING', true)
      )
    }, () => this.scrollToBottom())
    const persistRequestedImages = Promise.all(saveImages.map((item) => api.organizeFile('ATTACHMENT', item.id, {
      purposeCode: 'UNCLASSIFIED', relatedType: 'NONE', manualUpload: true
    })))
    persistRequestedImages.then(() => api.startAgentMessageStream(
      displayContent, this.data.conversationId, attachments.map((item) => item.id)
    )).then((result) => {
      const run = normalizeResult(result)
      if (!run) throw new Error('经营助手未返回任务信息')
      api.queueTemporaryAgentFiles(run.runId, transientAttachments)
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
  openWorkbench() { openPage('/pages/finance-ledger/index') },
  openMyFiles() {
    this.setData({ historyOpen: false })
    openPage('/pages/agent-files/index')
  },

  findGeneratedFile(id) {
    const fromRun = ((this.data.run && this.data.run.files) || []).find((item) => Number(item.id) === id)
    if (fromRun) return fromRun
    for (const message of this.data.messages) {
      const found = (message.files || []).find((item) => Number(item.id) === id)
      if (found) return found
    }
    return null
  },

  openGeneratedFile(event) {
    const id = Number(event.currentTarget.dataset.id)
    const file = this.findGeneratedFile(id)
    if (!file || this.data.openingFileId) return
    this.setData({ openingFileId: file.id })
    openAgentFile(file)
      .catch((error) => wx.showToast({ title: errorMessage(error, '文件打开失败，请稍后重试'), icon: 'none', duration: 3000 }))
      .finally(() => this.setData({ openingFileId: '' }))
  },
  openBillingDocument(event) {
    const id = Number(event.currentTarget.dataset.id)
    if (id) openPage('/pages/billing-editor/index', { id })
  },

  deleteGeneratedFile(event) {
    const id = Number(event.currentTarget.dataset.id)
    const file = this.findGeneratedFile(id)
    if (!file || this.data.deletingFileId) return
    this.setData({ deletingFileId: file.id })
    confirmDeleteAgentFile(file).then((deleted) => {
      if (!deleted) return
      const strip = (files) => (files || []).filter((item) => Number(item.id) !== id)
      const messages = this.data.messages.map((message) => (
        message.files && message.files.length ? { ...message, files: strip(message.files) } : message
      ))
      const run = this.data.run ? { ...this.data.run, files: strip(this.data.run.files) } : this.data.run
      this.setData({ messages, run })
      wx.showToast({ title: '文件已删除', icon: 'success' })
    }).catch((error) => wx.showToast({ title: errorMessage(error, '删除失败，请重试'), icon: 'none' }))
      .finally(() => this.setData({ deletingFileId: '' }))
  },
  openResultAction(event) {
    const target = event.currentTarget.dataset.target
    const params = event.currentTarget.dataset.params || {}
    const routes = {
      WORKBENCH: '/pages/workbench/index', PRODUCT_LIST: '/pages/products/index', PRODUCT_DETAIL: '/pages/product-detail/index',
      INVENTORY_LIST: '/pages/inventories/index', SALES_ORDER_DETAIL: '/pages/sales-detail/index',
      PURCHASE_ORDER_DETAIL: '/pages/purchase-detail/index', EXCEL_TASK_DETAIL: '/pages/excel-task-detail/index',
      PRICE_HISTORY: '/pages/price-history/index', BILLING_DOCUMENT: '/pages/billing-editor/index'
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
    if (!['COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED', 'PARTIAL'].includes(run.status)) {
      wx.setStorageSync(ACTIVE_RUN_KEY, run.runId)
      this.setData({ run, conversationId })
      return
    }
    api.cleanupTemporaryAgentFiles(run.runId).catch(() => {})
    wx.removeStorageSync(ACTIVE_RUN_KEY)
    this.finalizeStreamingMessage(run.output, run.status, run.files, run.billingDocumentId)
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

  finalizeStreamingMessage(content, status, files, documentId) {
    const messages = this.data.messages.slice()
    const index = messages.map((item) => item.streaming).lastIndexOf(true)
    const finalMessage = assistantMessage(content || '任务已结束', status, false, files || [])
    if (documentId) finalMessage.billingDocumentId = documentId
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
      const billingAction = approved ? billingDocumentAction(result) : null
      if (billingAction) setTimeout(() => openPage('/pages/billing-editor/index', billingAction.params), 350)
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
      run: null, conversationId: null, currentTitle: '小五', messages: [], input: '', historyOpen: false,
      sending: false, streaming: false, pendingAttachments: []
    })
  }
})
