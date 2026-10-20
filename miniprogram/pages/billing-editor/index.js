const api = require('../../utils/api')
const { fileView, openAgentFile } = require('../../utils/agent-files')

function today() {
  const date = new Date()
  const pad = (value) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function blankLine() {
  return {
    name: '', specification: '', unit: '', quantity: '', price: '', remark: '', alias: '',
    candidateKey: '', referenceSource: '', referenceCategory: '', referenceAliases: [],
    unitCandidates: [], referenceCaution: '', remember: false, sourceAmount: ''
  }
}

function decimal(value) {
  const text = String(value === null || value === undefined ? '' : value).trim()
  return text !== '' && /^\d+(?:\.\d+)?$/.test(text) ? Number(text) : null
}

function money(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : '0.00'
}

function lineView(line, index) {
  const quantity = line.quantity === null || line.quantity === undefined ? '' : String(line.quantity)
  const price = line.price === null || line.price === undefined ? '' : String(line.price)
  const q = decimal(quantity)
  const p = decimal(price)
  return {
    ...blankLine(), ...line, quantity, price, index: index + 1,
    amountText: q === null || p === null ? '—' : money(q * p),
    unitChoices: (line.unitCandidates || []).slice(0, 4)
  }
}

function formView(content) {
  const source = content || {}
  return {
    companyName: source.companyName || '恒迪模具五金',
    phone: source.phone || '',
    customerName: source.customerName || '',
    date: source.date || today(),
    remark: source.remark || '',
    sourceNote: source.sourceNote || ''
  }
}

function errorText(error, fallback) {
  const raw = typeof error === 'string'
    ? error
    : (error && (error.message || error.errMsg || error.error || error.data)) || ''
  const text = typeof raw === 'string' ? raw : JSON.stringify(raw || '')
  if (/No static resource\s+billing\/documents/i.test(text) || /billing\/documents.*404/i.test(text)) {
    return '云端开单服务尚未更新，请发布最新后端后重试'
  }
  return text || fallback
}

Page({
  data: {
    loading: true, error: '', busy: false, mode: 'preview', dirty: false,
    documentId: null, document: null, form: formView(), lines: [lineView(blankLine(), 0)],
    totalText: '0.00', incompleteCount: 1, searchKeyword: '', candidates: [], searching: false,
    statusText: '', generatedFile: null
  },

  onLoad(options) {
    const id = Number(options.id)
    if (id) this.loadDocument(id)
    else this.setData({ loading: false, mode: 'edit' }, () => this.recalculate())
  },

  loadDocument(id) {
    this.setData({ loading: true, error: '' })
    api.getBillingDocument(id).then((document) => this.showDocument(document, true))
      .catch((error) => this.setData({ error: errorText(error, '单据加载失败，请稍后重试') }))
      .finally(() => this.setData({ loading: false }))
  },

  retry() {
    if (this.data.documentId) this.loadDocument(this.data.documentId)
  },

  showDocument(document, keepPreview) {
    const content = document.content || {}
    this.setData({
      documentId: document.id,
      document,
      form: formView(content),
      lines: (content.lines && content.lines.length ? content.lines : [blankLine()]).map(lineView),
      mode: keepPreview ? 'preview' : this.data.mode,
      dirty: false,
      statusText: document.status === 'CONFIRMED' ? `已保存 · ${document.number}` : `草稿 #${document.id} · 已保存`
    }, () => this.recalculate())
  },

  setMode(event) {
    const mode = event.currentTarget.dataset.mode
    if (mode === 'edit' && this.data.document && this.data.document.status === 'CONFIRMED') {
      return wx.showToast({ title: '已保存单据不能直接修改', icon: 'none' })
    }
    this.setData({ mode })
  },

  changeForm(event) {
    const field = event.currentTarget.dataset.field
    this.setData({ [`form.${field}`]: event.detail.value, dirty: true, statusText: '有修改尚未保存' })
  },

  changeDate(event) {
    this.setData({ 'form.date': event.detail.value, dirty: true, statusText: '有修改尚未保存' })
  },

  changeLine(event) {
    const index = Number(event.currentTarget.dataset.index)
    const field = event.currentTarget.dataset.field
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], [field]: event.detail.value }
    this.setData({ lines, dirty: true, statusText: '有修改尚未保存' }, () => this.recalculate())
  },

  chooseUnit(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], unit: event.currentTarget.dataset.unit }
    this.setData({ lines, dirty: true, statusText: '有修改尚未保存' }, () => this.recalculate())
  },

  toggleRemember(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], remember: event.detail.value }
    this.setData({ lines, dirty: true, statusText: '有修改尚未保存' })
  },

  addLine() {
    this.setData({ lines: this.data.lines.concat(lineView(blankLine(), this.data.lines.length)), dirty: true }, () => this.recalculate())
  },

  removeLine(event) {
    if (this.data.lines.length === 1) return wx.showToast({ title: '至少保留一行商品', icon: 'none' })
    const remove = Number(event.currentTarget.dataset.index)
    this.setData({ lines: this.data.lines.filter((_, index) => index !== remove).map(lineView), dirty: true }, () => this.recalculate())
  },

  recalculate() {
    let total = 0
    let incompleteCount = 0
    const lines = this.data.lines.map((line, index) => {
      const next = lineView(line, index)
      const q = decimal(next.quantity)
      const p = decimal(next.price)
      if (q !== null && p !== null) total += q * p
      if (!next.name.trim() || !next.unit.trim() || q === null || p === null) incompleteCount += 1
      return next
    })
    this.setData({ lines, totalText: money(total), incompleteCount })
  },

  changeSearch(event) {
    const searchKeyword = event.detail.value || ''
    this.setData({ searchKeyword })
    clearTimeout(this.searchTimer)
    this.searchTimer = setTimeout(() => this.searchProducts(), 260)
  },

  searchProducts() {
    this.setData({ searching: true })
    api.searchBillingProducts(this.data.searchKeyword).then((candidates) => this.setData({ candidates: candidates || [] }))
      .catch(() => this.setData({ candidates: [] }))
      .finally(() => this.setData({ searching: false }))
  },

  chooseCandidate(event) {
    const index = Number(event.currentTarget.dataset.index)
    const candidate = this.data.candidates[index]
    if (!candidate) return
    const target = this.data.lines.findIndex((line) => !line.name.trim())
    const line = lineView({
      ...blankLine(), name: candidate.name || '', specification: candidate.specification || '',
      unit: candidate.unit || '', price: candidate.price === null || candidate.price === undefined ? '' : candidate.price,
      quantity: '1', alias: candidate.alias || '', candidateKey: candidate.candidateKey || '',
      referenceSource: candidate.source || '', referenceCategory: candidate.category || '',
      referenceAliases: candidate.aliases || [], unitCandidates: candidate.unitCandidates || [],
      referenceCaution: candidate.caution || ''
    }, target >= 0 ? target : this.data.lines.length)
    const lines = this.data.lines.slice()
    if (target >= 0) lines[target] = line
    else lines.push(line)
    this.setData({ lines, searchKeyword: '', candidates: [], dirty: true, statusText: '已加入商品，请填写数量和单价' }, () => this.recalculate())
  },

  payload() {
    return {
      ...this.data.form,
      version: this.data.document ? this.data.document.version : null,
      requestKey: this.data.document ? null : `mini-billing-${Date.now()}`,
      lines: this.data.lines.map((line) => ({
        ...line,
        index: undefined, amountText: undefined, unitChoices: undefined,
        name: line.name.trim(), specification: line.specification.trim(), unit: line.unit.trim(),
        quantity: line.quantity === '' ? null : line.quantity,
        price: line.price === '' ? null : line.price
      }))
    }
  },

  saveDraft() {
    if (this.data.busy) return Promise.resolve(null)
    this.setData({ busy: true, statusText: '正在保存…' })
    const task = this.data.documentId
      ? api.saveBillingDocument(this.data.documentId, this.payload())
      : api.createBillingDocument(this.payload())
    return task.then((document) => {
      this.showDocument(document, false)
      wx.showToast({ title: '草稿已保存', icon: 'success' })
      return document
    }).catch((error) => {
      this.setData({ statusText: '保存未完成' })
      wx.showToast({ title: errorText(error, '保存失败，请重试'), icon: 'none', duration: 3000 })
      return null
    }).finally(() => this.setData({ busy: false }))
  },

  previewDocument() {
    this.recalculate()
    this.setData({ mode: 'preview' })
  },

  confirmDocument() {
    if (this.data.incompleteCount) return wx.showToast({ title: `还有 ${this.data.incompleteCount} 行需要补齐`, icon: 'none' })
    wx.showModal({
      title: '保存这张销售单？',
      content: `共 ${this.data.lines.length} 项，合计 ¥${this.data.totalText}。保存后可生成 Excel，本阶段不会登记销售和欠款。`,
      confirmText: '保存并导出'
    }).then((choice) => {
      if (!choice.confirm) return
      return this.saveDraft().then((draft) => {
        if (!draft) return
        this.setData({ busy: true, statusText: '正在保存正式单据…' })
        return api.confirmBillingDocument(draft.id, draft.version).then((document) => {
          this.showDocument(document, true)
          return this.exportExcel(document.id)
        }).catch((error) => wx.showToast({ title: errorText(error, '单据保存失败'), icon: 'none', duration: 3000 }))
          .finally(() => this.setData({ busy: false }))
      })
    })
  },

  exportExcel(documentId) {
    const requestedId = Number(documentId)
    const id = Number.isFinite(requestedId) && requestedId > 0 ? requestedId : Number(this.data.documentId)
    if (!id) return Promise.resolve()
    this.setData({ busy: true, statusText: '正在生成 Excel…' })
    return api.exportBillingDocument(id).then((result) => {
      if (result.status !== 'READY' || !result.outputId) throw new Error('Excel 尚未生成成功，请重试')
      const generatedFile = fileView({ id: result.outputId, name: result.fileName, mimeType: result.mimeType || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', status: result.status, size: result.size })
      this.setData({ generatedFile, statusText: 'Excel 已生成，可以打开或转发' })
      return openAgentFile(generatedFile)
    }).catch((error) => wx.showToast({ title: errorText(error, 'Excel 生成失败'), icon: 'none', duration: 3000 }))
      .finally(() => this.setData({ busy: false }))
  },

  openGenerated() {
    if (this.data.generatedFile) openAgentFile(this.data.generatedFile)
  }
})
