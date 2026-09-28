const api = require('../../utils/api')

const ACTIVE_RUN_KEY = 'agent_active_run_id'

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

Page({
  data: {
    input: '', sending: false, resolving: false, run: null, messages: [],
    suggestions: ['查一下电钻库存', '给老王开两把电钻，按以前价格记账', '看看 12 号销售草稿']
  },

  onShow() {
    const runId = wx.getStorageSync(ACTIVE_RUN_KEY)
    if (runId) this.loadRun(runId)
  },

  onPullDownRefresh() {
    const runId = this.data.run && this.data.run.runId
    if (!runId) return wx.stopPullDownRefresh()
    this.loadRun(runId).finally(() => wx.stopPullDownRefresh())
  },

  updateInput(event) { this.setData({ input: event.detail.value }) },
  useSuggestion(event) { this.setData({ input: event.currentTarget.dataset.text || '' }) },

  send() {
    const content = (this.data.input || '').trim()
    if (!content || this.data.sending) return
    this.setData({
      input: '', sending: true,
      messages: this.data.messages.concat({ role: 'user', content })
    })
    api.sendAgentMessage(content).then((result) => this.applyRun(result, true)).catch((error) => {
      this.setData({ messages: this.data.messages.concat({
        role: 'error', content: errorMessage(error, '经营助手暂时无法处理，请稍后重试')
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
    const update = { run }
    if (appendMessage && run.output && run.status !== 'WAITING_APPROVAL') {
      update.messages = this.data.messages.concat({ role: 'assistant', content: run.output })
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
      wx.showToast({ title: approved ? '操作已确认' : '操作已取消', icon: 'none' })
    }).catch((error) => {
      wx.showToast({ title: errorMessage(error, '确认处理失败'), icon: 'none' })
      return this.loadRun(run.runId)
    }).finally(() => this.setData({ resolving: false }))
  },

  startNew() {
    wx.removeStorageSync(ACTIVE_RUN_KEY)
    this.setData({ run: null, messages: [], input: '' })
  }
})
