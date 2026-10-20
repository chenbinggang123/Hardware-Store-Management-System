const api = require('../../utils/api')
const { fileView, openAgentFile } = require('../../utils/agent-files')
const { renderAndSave } = require('../../utils/billing-image')

function today() {
  const date = new Date()
  const pad = (value) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function blankLine() {
  return {
    name: '', specification: '', unit: '', quantity: '', price: '', remark: '', alias: '',
    candidateKey: '', referenceSource: '', referenceCategory: '', referenceAliases: [],
    unitCandidates: [], referenceCaution: '', remember: false, sourceAmount: '', remarkOpen: false
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

function validUnit(value) {
  const unit = String(value || '').trim()
  return Boolean(unit) && !/^(?:1|￥|¥|元|[.·,，、-])$/.test(unit)
}

function sourceAmountNumber(value) {
  const matches = String(value || '').replace(/,/g, '').match(/-?\d+(?:\.\d+)?/g)
  if (!matches || !matches.length) return null
  const number = Number(matches[matches.length - 1])
  return Number.isFinite(number) ? number : null
}

function lineView(line, index) {
  const quantity = line.quantity === null || line.quantity === undefined ? '' : String(line.quantity)
  const price = line.price === null || line.price === undefined ? '' : String(line.price)
  const q = decimal(quantity)
  const p = decimal(price)
  const sourceAmount = sourceAmountNumber(line.sourceAmount)
  const calculated = q === null || p === null ? null : q * p
  const amountMismatch = sourceAmount !== null && calculated !== null && Math.abs(sourceAmount - calculated) > 0.009
  const caution = `${line.referenceCaution || ''} ${line.remark || ''}`
  return {
    ...blankLine(), ...line, quantity, price, index: index + 1,
    remarkOpen: line.remarkOpen === undefined ? Boolean(line.remark) : Boolean(line.remarkOpen),
    remarkToggleText: line.remarkOpen === undefined ? (line.remark ? '收起备注' : '＋ 添加备注') : (line.remarkOpen ? '收起备注' : (line.remark ? '查看备注' : '＋ 添加备注')),
    amountText: calculated === null ? '—' : money(calculated),
    unitChoices: (line.unitCandidates || []).slice(0, 4),
    nameUncertain: !String(line.name || '').trim() || /名称.*(?:疑|截断|待确认)/.test(caution),
    unitUncertain: !validUnit(line.unit) || /单位.*(?:未识别|待确认|疑)/.test(caution),
    quantityUncertain: q === null || q <= 0,
    priceUncertain: p === null || p < 0 || amountMismatch,
    amountMismatch
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
    totalText: '0.00', incompleteCount: 1, reviewCount: 4, searchKeyword: '', candidates: [], searching: false,
    candidateTitle: '最近常用', activeLineIndex: null, evidenceOpen: false,
    statusText: '', actionError: '', actionRetry: '', actionRetryText: '', imageBusy: false, generatedFile: null
  },

  onLoad(options) {
    const id = Number(options.id)
    if (id) this.loadDocument(id)
    else this.setData({ loading: false, mode: 'edit' }, () => this.recalculate())
    this.searchProducts('')
  },

  onUnload() { clearTimeout(this.searchTimer) },

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
    if (mode === 'edit' && !this.data.searchKeyword) this.searchProducts('')
  },

  changeForm(event) {
    const field = event.currentTarget.dataset.field
    this.setData({ [`form.${field}`]: event.detail.value, dirty: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '有修改尚未保存' })
  },

  changeDate(event) {
    this.setData({ 'form.date': event.detail.value, dirty: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '有修改尚未保存' })
  },

  changeLine(event) {
    const index = Number(event.currentTarget.dataset.index)
    const field = event.currentTarget.dataset.field
    const lines = this.data.lines.slice()
    const learnIdentity = ['name', 'specification', 'unit'].includes(field) && String(event.detail.value || '').trim()
    lines[index] = { ...lines[index], [field]: event.detail.value, remember: learnIdentity ? true : lines[index].remember }
    this.setData({ lines, dirty: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '有修改尚未保存' }, () => this.recalculate())
    if (field === 'name') {
      this.setData({ activeLineIndex: index, searchKeyword: event.detail.value || '' })
      clearTimeout(this.searchTimer)
      this.searchTimer = setTimeout(() => this.searchProducts(), 220)
    }
  },

  focusLineName(event) {
    const index = Number(event.currentTarget.dataset.index)
    const keyword = (this.data.lines[index] && this.data.lines[index].name) || ''
    this.focusLineField(index, 'name')
    this.setData({ activeLineIndex: index, searchKeyword: keyword }, () => this.searchProducts())
  },

  focusLineInput(event) {
    this.focusLineField(Number(event.currentTarget.dataset.index), event.currentTarget.dataset.field)
  },

  focusGlobalSearch() {
    this.setData({ activeLineIndex: null }, () => this.searchProducts())
  },

  focusLineField(index, field) {
    const lines = this.data.lines.map((line, lineIndex) => ({
      ...line,
      nameFocus: lineIndex === index && field === 'name',
      quantityFocus: lineIndex === index && field === 'quantity',
      priceFocus: lineIndex === index && field === 'price'
    }))
    this.setData({ lines })
  },

  confirmLineField(event) {
    const index = Number(event.currentTarget.dataset.index)
    const field = event.currentTarget.dataset.field
    if (field === 'quantity') return this.focusLineField(index, 'price')
    if (field !== 'price') return
    if (index < this.data.lines.length - 1) return this.focusLineField(index + 1, 'name')
    this.addLine()
  },

  chooseUnit(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], unit: event.currentTarget.dataset.unit, remember: true }
    this.setData({ lines, dirty: true, statusText: '有修改尚未保存' }, () => this.recalculate())
  },

  toggleRemember(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], remember: event.detail.value }
    this.setData({ lines, dirty: true, statusText: '有修改尚未保存' })
  },

  toggleLineRemark(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], remarkOpen: !lines[index].remarkOpen }
    this.setData({ lines })
  },

  addLine() {
    const index = this.data.lines.length
    this.setData({ lines: this.data.lines.concat(lineView(blankLine(), index)), dirty: true }, () => {
      this.recalculate()
      this.focusLineField(index, 'name')
    })
  },

  duplicateLine(event) {
    const index = Number(event.currentTarget.dataset.index)
    const source = this.data.lines[index]
    if (!source) return
    const copy = lineView({ ...source, quantity: '1', remark: '', remarkOpen: false, sourceAmount: '', amountMismatch: false, remember: true }, index + 1)
    const lines = this.data.lines.slice()
    lines.splice(index + 1, 0, copy)
    this.setData({ lines: lines.map(lineView), dirty: true, statusText: '已复制商品，请填写数量' }, () => {
      this.recalculate()
      this.focusLineField(index + 1, 'quantity')
    })
  },

  removeLine(event) {
    if (this.data.lines.length === 1) return wx.showToast({ title: '至少保留一行商品', icon: 'none' })
    const remove = Number(event.currentTarget.dataset.index)
    this.setData({ lines: this.data.lines.filter((_, index) => index !== remove).map(lineView), dirty: true }, () => this.recalculate())
  },

  recalculate() {
    let total = 0
    let incompleteCount = 0
    let reviewCount = 0
    const lines = this.data.lines.map((line, index) => {
      const next = lineView(line, index)
      const q = decimal(next.quantity)
      const p = decimal(next.price)
      if (q !== null && p !== null) total += q * p
      if (!next.name.trim() || !validUnit(next.unit) || q === null || q <= 0 || p === null || p < 0) incompleteCount += 1
      reviewCount += [next.nameUncertain, next.unitUncertain, next.quantityUncertain, next.priceUncertain].filter(Boolean).length
      return next
    })
    this.setData({ lines, totalText: money(total), incompleteCount, reviewCount })
  },

  changeSearch(event) {
    const searchKeyword = event.detail.value || ''
    this.setData({ searchKeyword })
    clearTimeout(this.searchTimer)
    this.searchTimer = setTimeout(() => this.searchProducts(), 260)
  },

  searchProducts(keywordOverride) {
    const keyword = typeof keywordOverride === 'string' ? keywordOverride : this.data.searchKeyword
    const requestId = (this.searchRequestId || 0) + 1
    this.searchRequestId = requestId
    this.setData({ searching: true })
    api.searchBillingProducts(keyword).then((candidates) => {
      if (requestId !== this.searchRequestId) return
      this.setData({ candidates: candidates || [], candidateTitle: keyword.trim() ? '匹配商品' : '最近常用' })
    }).catch(() => {
      if (requestId === this.searchRequestId) this.setData({ candidates: [] })
    }).finally(() => {
      if (requestId === this.searchRequestId) this.setData({ searching: false })
    })
  },

  chooseCandidate(event) {
    const index = Number(event.currentTarget.dataset.index)
    const candidate = this.data.candidates[index]
    if (!candidate) return
    const requestedTarget = this.data.activeLineIndex === null || this.data.activeLineIndex === undefined
      ? -1 : Number(this.data.activeLineIndex)
    const target = Number.isInteger(requestedTarget) && requestedTarget >= 0 && requestedTarget < this.data.lines.length
      ? requestedTarget : this.data.lines.findIndex((line) => !line.name.trim())
    const existing = target >= 0 ? this.data.lines[target] : blankLine()
    const line = lineView({
      ...existing, name: candidate.name || existing.name || '', specification: candidate.specification || existing.specification || '',
      unit: candidate.unit || existing.unit || '', price: candidate.price === null || candidate.price === undefined ? existing.price || '' : candidate.price,
      quantity: existing.quantity || '1', alias: candidate.alias || '', candidateKey: candidate.candidateKey || '',
      referenceSource: candidate.source || '', referenceCategory: candidate.category || '',
      referenceAliases: candidate.aliases || [], unitCandidates: candidate.unitCandidates || [],
      referenceCaution: candidate.caution || '', remember: true
    }, target >= 0 ? target : this.data.lines.length)
    const lines = this.data.lines.slice()
    if (target >= 0) lines[target] = line
    else lines.push(line)
    this.setData({ lines, searchKeyword: '', candidates: [], activeLineIndex: null, dirty: true, statusText: '商品信息已带入，请核对数量和单价' }, () => {
      this.recalculate()
      this.focusLineField(target >= 0 ? target : lines.length - 1, 'quantity')
    })
  },

  toggleEvidence() { this.setData({ evidenceOpen: !this.data.evidenceOpen }) },

  payload() {
    return {
      ...this.data.form,
      version: this.data.document ? this.data.document.version : null,
      requestKey: this.data.document ? null : `mini-billing-${Date.now()}`,
      lines: this.data.lines.map((line) => ({
        ...line,
        index: undefined, amountText: undefined, unitChoices: undefined,
        nameFocus: undefined, quantityFocus: undefined, priceFocus: undefined,
        nameUncertain: undefined, unitUncertain: undefined, quantityUncertain: undefined,
        priceUncertain: undefined, amountMismatch: undefined, remarkOpen: undefined, remarkToggleText: undefined,
        name: line.name.trim(), specification: line.specification.trim(), unit: line.unit.trim(),
        quantity: line.quantity === '' ? null : line.quantity,
        price: line.price === '' ? null : line.price
      }))
    }
  },

  saveDraft() {
    if (this.data.busy) return Promise.resolve(null)
    this.setData({ busy: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '正在保存…' })
    const task = this.data.documentId
      ? api.saveBillingDocument(this.data.documentId, this.payload())
      : api.createBillingDocument(this.payload())
    return task.then((document) => {
      this.showDocument(document, false)
      wx.showToast({ title: '草稿已保存', icon: 'success' })
      return document
    }).catch((error) => {
      const message = errorText(error, '保存失败，请重试')
      this.setData({ statusText: '保存未完成', actionError: `草稿没有保存：${message}`, actionRetry: '', actionRetryText: '' })
      wx.showToast({ title: message, icon: 'none', duration: 3000 })
      return null
    }).finally(() => this.setData({ busy: false }))
  },

  previewDocument() {
    this.recalculate()
    this.setData({ mode: 'preview' })
  },

  confirmDocument() {
    if (this.data.busy) return Promise.resolve()
    if (this.data.incompleteCount) {
      const message = `还有 ${this.data.incompleteCount} 行需要补齐名称、单位、数量和单价`
      this.setData({ actionError: message, actionRetry: '', actionRetryText: '' })
      return wx.showToast({ title: message, icon: 'none', duration: 3000 })
    }
    this.setData({ actionError: '', actionRetry: '', actionRetryText: '' })
    return wx.showModal({
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
        }).catch((error) => {
          const message = errorText(error, '单据保存失败')
          this.setData({ actionError: `单据没有确认：${message}`, actionRetry: '', actionRetryText: '', statusText: '正式单据保存未完成' })
          wx.showToast({ title: message, icon: 'none', duration: 3000 })
        })
          .finally(() => this.setData({ busy: false }))
      })
    })
  },

  exportExcel(documentId) {
    const requestedId = Number(documentId)
    const id = Number.isFinite(requestedId) && requestedId > 0 ? requestedId : Number(this.data.documentId)
    if (!id) return Promise.resolve()
    this.setData({ busy: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '正在生成 Excel…' })
    return api.exportBillingDocument(id).then((result) => {
      if (result.status !== 'READY' || !result.outputId) throw new Error('Excel 尚未生成成功，请重试')
      const generatedFile = fileView({ id: result.outputId, name: result.fileName, mimeType: result.mimeType || 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', status: result.status, size: result.fileSize || result.size })
      this.setData({ generatedFile, statusText: 'Excel 已生成，可以打开或转发' })
      return this.openGenerated()
    }).catch((error) => {
      const message = errorText(error, 'Excel 生成失败')
      this.setData({ actionError: `Excel 没有生成：${message}`, actionRetry: 'excel', actionRetryText: '重试导出', statusText: 'Excel 生成未完成' })
      wx.showToast({ title: message, icon: 'none', duration: 3000 })
    })
      .finally(() => this.setData({ busy: false }))
  },

  openGenerated() {
    if (!this.data.generatedFile) return Promise.resolve()
    return openAgentFile(this.data.generatedFile).catch((error) => {
      const message = errorText(error, '文件没有自动打开')
      this.setData({ actionError: `Excel 已经保存，但没有自动打开：${message}。`, actionRetry: 'openExcel', actionRetryText: '重新打开' })
      wx.showToast({ title: 'Excel 已保存，请重试打开', icon: 'none', duration: 3000 })
    })
  },

  saveAsImage() {
    if (this.data.imageBusy) return Promise.resolve()
    this.setData({ imageBusy: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '正在生成单据图片…' })
    const content = { ...this.data.form, lines: this.data.lines }
    return renderAndSave(this, content, { number: this.data.document && this.data.document.number, total: this.data.totalText })
      .then(() => {
        this.setData({ statusText: '单据图片已保存到手机相册' })
        wx.showToast({ title: '图片已保存到相册', icon: 'success' })
      }).catch((error) => {
        const message = errorText(error, '图片保存失败')
        this.setData({ actionError: `图片没有保存：${message}`, actionRetry: 'image', actionRetryText: '重新保存', statusText: '图片保存未完成' })
        wx.showToast({ title: message, icon: 'none', duration: 3000 })
      }).finally(() => this.setData({ imageBusy: false }))
  },

  retryAction() {
    if (this.data.actionRetry === 'image') return this.saveAsImage()
    if (this.data.actionRetry === 'openExcel') return this.openGenerated()
    if (this.data.actionRetry === 'excel') return this.exportExcel()
  }
})

