const api = require('../../utils/api')
const { fileView, openAgentFile } = require('../../utils/agent-files')
const { renderAndSave } = require('../../utils/billing-image')
const localDraft = require('../../utils/billing-local-draft')
const { openPage } = require('../../utils/router')

function today() {
  const date = new Date()
  const pad = (value) => `${value}`.padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function blankLine() {
  return {
    name: '', specification: '', unit: '', quantity: '', price: '', remark: '', alias: '',
    candidateKey: '', referenceSource: '', referenceCategory: '', referenceAliases: [],
    unitCandidates: [], referenceCaution: '', remember: false, sourceAmount: '', remarkOpen: false, expanded: false
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
  const nameUncertain = !String(line.name || '').trim() || /名称.*(?:疑|截断|待确认)/.test(caution)
  const unitUncertain = !validUnit(line.unit) || /单位.*(?:未识别|待确认|疑)/.test(caution)
  const quantityUncertain = q === null || q <= 0
  const priceUncertain = p === null || p < 0 || amountMismatch
  return {
    ...blankLine(), ...line, quantity, price, index: index + 1,
    remarkOpen: line.remarkOpen === undefined ? Boolean(line.remark) : Boolean(line.remarkOpen),
    remarkToggleText: line.remarkOpen === undefined ? (line.remark ? '收起备注' : '＋ 添加备注') : (line.remarkOpen ? '收起备注' : (line.remark ? '查看备注' : '＋ 添加备注')),
    amountText: calculated === null ? '—' : money(calculated),
    unitChoices: (line.unitCandidates || []).slice(0, 4),
    nameUncertain, unitUncertain, quantityUncertain, priceUncertain, amountMismatch,
    needsReview: nameUncertain || unitUncertain || quantityUncertain || priceUncertain,
    inCatalog: String(line.candidateKey || '').indexOf('product:') === 0,
    expanded: Boolean(line.expanded)
  }
}

function formView(content) {
  const source = content || {}
  return {
    companyName: source.companyName || '恒迪模具五金',
    phone: source.phone || '',
    customerName: source.customerName || '',
    ledgerId: source.ledgerId || null,
    documentType: ['RETURN', 'PROCUREMENT'].includes(source.documentType) ? source.documentType : 'PURCHASE',
    date: source.date === undefined || source.date === null ? today() : source.date,
    remark: source.remark || '',
    sourceNote: source.sourceNote || '',
    sourceDocumentNumber: source.sourceDocumentNumber || ''
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
  if (/network socket|secure TLS|connection.*established/i.test(text)) {
    return '当前环境无法写入系统相册，请在微信真机中重试'
  }
  if (/privacy agreement|api scope is not declared/i.test(text)) {
    return '小程序后台尚未声明“使用你的相册（仅写入）”权限'
  }
  return text || fallback
}

Page({
  data: {
    loading: true, error: '', busy: false, mode: 'preview', dirty: false,
    documentId: null, document: null, form: formView(), lines: [lineView({ ...blankLine(), expanded: true }, 0)],
    totalText: '0.00', incompleteCount: 1, reviewCount: 4, reviewLineCount: 1, searchKeyword: '', candidates: [], searching: false,
    headerOpen: false, reviewOnly: false, localSaveText: '',
    ledgers: [], ledgerNames: ['不记入账本'], ledgerIndex: 0, ledgerName: '', ledgerPosting: null, ledgerBusy: false,
    candidateTitle: '最近常用', activeLineIndex: null, evidenceOpen: false,
    statusText: '', actionError: '', actionRetry: '', actionRetryText: '', imageBusy: false, generatedFile: null
  },

  onLoad(options = {}) {
    const id = Number(options.id)
    this.localStorageKey = localDraft.key(id)
    this.newRequestKey = `mini-billing-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
    if (id) { this.setData({ documentId: id }); this.loadDocument(id) }
    else this.setData({ loading: false, mode: 'edit' }, () => {
      this.offerLocalDraft(() => {
        if (options.ledgerId) this.setData({ 'form.ledgerId': Number(options.ledgerId) })
        if (['RETURN', 'PROCUREMENT'].includes(options.documentType)) this.setData({ 'form.documentType': options.documentType, headerOpen: options.documentType === 'PROCUREMENT' })
        if (options.factory) this.setData({ 'form.customerName': options.factory, headerOpen: true })
        this.recalculate()
        if (options.productId) this.prefillProduct(options.productId)
        if (options.copyFrom) this.copyFrom(options.copyFrom)
      })
    })
    this.searchProducts('')
    this.loadLedgers()
  },

  updateDraft(values, callback) {
    this.setData(values, callback)
    this.persistLocalDraft()
  },
  changeDocumentType(event) {
    if (this.data.document && this.data.document.status !== 'DRAFT') return
    const type = event.currentTarget.dataset.type
    this.updateDraft({ 'form.documentType': type, ...(type === 'PROCUREMENT' ? { 'form.ledgerId': null, ledgerIndex: 0, ledgerName: '', headerOpen: true } : {}), dirty: true })
  },

  persistLocalDraft() {
    if (!this.data.dirty || !this.localStorageKey) return
    const saved = localDraft.write(this.localStorageKey, {
      form: this.data.form, lines: this.data.lines,
      document: this.data.document, requestKey: this.newRequestKey
    })
    this.setData({ localSaveText: saved ? '已暂存到本机，尚未保存到开单记录' : '本机暂存失败，请先保存草稿再离开' })
    if (!this.leaveAlertEnabled && wx.enableAlertBeforeUnload) {
      wx.enableAlertBeforeUnload({ message: '单据尚未保存，请确认已暂存或先保存草稿。' })
      this.leaveAlertEnabled = true
    }
  },

  offerLocalDraft(startFresh) {
    if (this.recoveryOffered) return
    this.recoveryOffered = true
    const saved = localDraft.read(this.localStorageKey)
    if (!saved) { if (startFresh) startFresh(); return }
    const current = this.data.document
    const changed = current && (!saved.document || current.version !== saved.document.version || current.status !== 'DRAFT')
    return wx.showModal({
      title: changed ? '发现未保存的本机内容' : '继续上次未完成的开单？',
      content: changed ? '服务器上的单据已更新。恢复后将作为新单保存，避免覆盖已有单据。' : '本机保留了上次填写的客户和商品明细。可以继续填写，也可以丢弃暂存。',
      confirmText: changed ? '恢复为新单' : '继续填写', cancelText: '丢弃暂存'
    }).then((choice) => {
      if (!choice.confirm) {
        localDraft.remove(this.localStorageKey)
        if (startFresh) startFresh()
        return
      }
      if ((!changed || !saved.document) && saved.requestKey) this.newRequestKey = saved.requestKey
      this.updateDraft({
        form: formView(saved.form), lines: (saved.lines.length ? saved.lines : [blankLine()]).map((line, index) => lineView({ ...line, nameFocus: false, quantityFocus: false, priceFocus: false }, index)),
        document: changed ? null : (current || saved.document || null),
        documentId: changed ? null : ((current || saved.document || {}).id || null),
        mode: 'edit', dirty: true, statusText: '已恢复上次未保存的内容'
      }, () => { this.recalculate(); this.syncLedgerIndex(false) })
    }).catch(() => {
      this.setData({ localSaveText: '暂存仍保留，请重新进入后恢复' })
    })
  },

  onHide() { this.persistLocalDraft() },

  // 复制为新单：带入原单的客户和商品，日期改为今天，保存后是一张新单号的单据。
  copyFrom(sourceId) {
    api.getBillingDocument(sourceId).then((source) => {
      const content = (source && source.content) || {}
      const lines = (content.lines && content.lines.length ? content.lines : [blankLine()])
        .map((line) => ({ ...line, expanded: false }))
      this.updateDraft({
        form: { ...formView(content), date: today(), sourceDocumentNumber: '' },
        lines: lines.map(lineView),
        dirty: true,
        statusText: `从 ${source.number || '原单'} 复制，尚未保存`
      }, () => {
        this.recalculate()
        this.syncLedgerIndex(true)
      })
    }).catch(() => wx.showToast({ title: '原单加载失败', icon: 'none' }))
  },

  copyAsNew() {
    if (!this.data.documentId) return
    openPage('/pages/billing-editor/index', { copyFrom: this.data.documentId })
  },

  // 修改已保存的单据：退回草稿继续改。已记账的会先冲销，改完点“保存并记账”重新记一笔。
  reopenDocument() {
    if (this.data.ledgerPosting) return wx.showToast({ title: '已进入账本，请到账本处理', icon: 'none' })
    const document = this.data.document
    if (!document || document.status !== 'CONFIRMED' || this.data.busy) return
    const posted = this.data.ledgerPosting && !this.data.ledgerPosting.reversed
    wx.showModal({
      title: '修改这张单？',
      content: posted
        ? `「${this.data.ledgerPosting.factoryName}」账本里的这笔会先冲销，改完点“保存并记账”重新记一笔。`
        : '单据会退回草稿，改完再保存。',
      confirmText: '修改'
    }).then((choice) => {
      if (!choice.confirm) return
      this.setData({ busy: true })
      return api.reopenBillingDocument(document.id)
        .then(() => this.loadDocument(document.id))
        .catch((error) => wx.showToast({ title: errorText(error, '无法修改'), icon: 'none', duration: 3000 }))
        .finally(() => this.setData({ busy: false }))
    })
  },

  // 作废：单据标为作废；如果已记入厂家账本，后端会自动红字冲销。
  voidDocument() {
    if (this.data.ledgerPosting) return wx.showToast({ title: '已进入账本，请到账本处理', icon: 'none' })
    const document = this.data.document
    if (!document || document.status !== 'CONFIRMED' || this.data.busy) return
    const posted = this.data.ledgerPosting && !this.data.ledgerPosting.reversed
    wx.showModal({
      title: '作废这张单？',
      content: '',
      editable: true,
      placeholderText: posted ? `会同时冲销「${this.data.ledgerPosting.factoryName}」的这笔拿货；写一句原因` : '写一句原因，例如开错了',
      confirmText: '作废'
    }).then((choice) => {
      if (!choice.confirm) return
      this.setData({ busy: true })
      return api.voidBillingDocument(document.id, choice.content || '开错了')
        .then(() => {
          wx.showToast({ title: posted ? '已作废并冲销账本' : '已作废', icon: 'success' })
          this.loadDocument(document.id)
        })
        .catch((error) => wx.showToast({ title: errorText(error, '作废失败'), icon: 'none', duration: 3000 }))
        .finally(() => this.setData({ busy: false }))
    })
  },

  // 从商品库点“开单”进来：第一行带入该商品，光标停在数量。
  prefillProduct(productId) {
    api.getProduct(productId).then((product) => {
      if (!product) return
      const line = lineView({
        ...blankLine(), name: product.name || '', specification: product.spec || '', unit: product.unit || '',
        price: product.retailPrice === null || product.retailPrice === undefined ? '' : product.retailPrice,
        quantity: '1', candidateKey: `product:${product.id}`, referenceSource: '商品库', remember: true, expanded: true
      }, 0)
      this.updateDraft({ lines: [line], dirty: true }, () => {
        this.recalculate()
        this.focusLineField(0, 'quantity')
      })
    }).catch(() => {})
  },

  // 厂家账本：开单时选一家，保存单据后自动记为这家的“拿货”。
  loadLedgers() {
    return api.getFactoryLedgers({}).then((result) => {
      const ledgers = ((result && result.ledgers) || []).map((item) => ({ id: item.id, factoryName: item.factoryName }))
      this.setData({ ledgers, ledgerNames: ['不记入账本'].concat(ledgers.map((item) => item.factoryName)) }, () => this.syncLedgerIndex(true))
    }).catch(() => {})
  },

  syncLedgerIndex(autoMatch) {
    if (this.data.form.documentType === 'PROCUREMENT') return
    const ledgers = this.data.ledgers
    if (!ledgers.length) return // 账本列表还没加载完，保留单据里已选的账本
    let ledgerId = this.data.form.ledgerId
    if (!ledgerId && autoMatch && !(this.data.document && this.data.document.status === 'CONFIRMED')) {
      const name = String(this.data.form.customerName || '').trim()
      const matched = name && ledgers.find((item) => item.factoryName === name)
      if (matched) ledgerId = matched.id
    }
    const index = ledgers.findIndex((item) => item.id === ledgerId)
    this.updateDraft({
      'form.ledgerId': index >= 0 ? ledgerId : null,
      ledgerIndex: index >= 0 ? index + 1 : 0,
      ledgerName: index >= 0 ? ledgers[index].factoryName : ''
    })
  },

  changeLedger(event) {
    const index = Number(event.detail.value)
    const ledger = index > 0 ? this.data.ledgers[index - 1] : null
    const update = {
      'form.ledgerId': ledger ? ledger.id : null, ledgerIndex: ledger ? index : 0, ledgerName: ledger ? ledger.factoryName : '',
      dirty: true, statusText: '有修改尚未保存'
    }
    if (ledger && !String(this.data.form.customerName || '').trim()) update['form.customerName'] = ledger.factoryName
    this.updateDraft(update)
  },

  loadLedgerPosting(id) {
    if (!id) return Promise.resolve(null)
    return api.getBillingLedgerPosting(id).then((posting) => {
      this.setData({ ledgerPosting: posting && posting.posted ? posting : null })
      return posting
    }).catch(() => null)
  },

  postToLedger(documentId) {
    const id = Number(documentId) || Number(this.data.documentId)
    const ledgerId = this.data.form.ledgerId
    if (this.data.form.documentType === 'PROCUREMENT') return Promise.resolve(null)
    if (!id || !ledgerId || this.data.ledgerBusy) return Promise.resolve(null)
    this.setData({ ledgerBusy: true, statusText: '正在记入厂家账本…' })
    return api.postBillingToLedger(id, ledgerId).then((posting) => {
      this.setData({ ledgerPosting: posting && posting.posted ? posting : null, actionError: '', actionRetry: '', actionRetryText: '', statusText: `已记入 ${posting.factoryName} 的账本` })
      wx.showToast({ title: `已记入 ${posting.factoryName}`, icon: 'success' })
      return posting
    }).catch((error) => {
      const message = errorText(error, '记账失败')
      this.setData({ actionError: `单据已保存，但没有记入账本：${message}`, actionRetry: 'ledger', actionRetryText: '重新记账', statusText: '记账未完成' })
      wx.showToast({ title: message, icon: 'none', duration: 3000 })
      return null
    }).finally(() => this.setData({ ledgerBusy: false }))
  },

  // 已保存但还没记账的单据：选厂家后直接补记。
  chooseLedgerForSaved(event) {
    const index = Number(event.detail.value)
    const ledger = index > 0 ? this.data.ledgers[index - 1] : null
    if (!ledger) return
    this.setData({ 'form.ledgerId': ledger.id, ledgerIndex: index, ledgerName: ledger.factoryName }, () => this.postToLedger())
  },

  onUnload() { clearTimeout(this.searchTimer); this.persistLocalDraft() },

  loadDocument(id) {
    this.setData({ loading: true, error: '' })
    return api.getBillingDocument(id).then((document) => {
      this.showDocument(document, true)
      return this.offerLocalDraft()
    })
      .catch((error) => {
        const saved = localDraft.read(this.localStorageKey)
        if (saved && !this.recoveryOffered) {
          this.setData({ error: '', document: saved.document || null })
          return this.offerLocalDraft(() => this.setData({ error: errorText(error, '单据加载失败，请稍后重试') }))
        }
        this.setData({ error: errorText(error, '单据加载失败，请稍后重试') })
      })
      .finally(() => this.setData({ loading: false }))
  },

  retry() {
    if (this.data.documentId) this.loadDocument(this.data.documentId)
  },

  showDocument(document, fresh) {
    if (this.leaveAlertEnabled && wx.disableAlertBeforeUnload) wx.disableAlertBeforeUnload()
    this.leaveAlertEnabled = false
    const content = document.content || {}
    const confirmed = document.status === 'CONFIRMED' || document.status === 'VOIDED'
    // 保存草稿后保留当前展开的行；重新打开的草稿直接进入紧凑核对视图，已保存单据进入预览。
    const previous = fresh ? [] : this.data.lines
    const lines = (content.lines && content.lines.length ? content.lines : [blankLine()])
      .map((line, index) => ({ ...line, expanded: Boolean(previous[index] && previous[index].expanded) }))
    this.setData({
      documentId: document.id,
      document,
      form: formView(content),
      lines: lines.map(lineView),
      mode: confirmed ? 'preview' : (fresh ? 'edit' : this.data.mode),
      reviewOnly: fresh ? false : this.data.reviewOnly,
      dirty: false, localSaveText: '',
      statusText: document.status === 'VOIDED' ? `已作废 · ${document.number}` : (document.status === 'CONFIRMED' ? `已保存 · ${document.number}` : `草稿 #${document.id} · 已保存`)
    }, () => {
      this.recalculate()
      this.syncLedgerIndex(fresh)
      if (confirmed) this.loadLedgerPosting(document.id)
      else this.setData({ ledgerPosting: null })
    })
  },

  setMode(event) {
    const mode = event.currentTarget.dataset.mode
    if (mode === 'edit' && this.data.document && this.data.document.status === 'CONFIRMED') return this.reopenDocument()
    if (mode === 'edit' && this.data.document && this.data.document.status === 'VOIDED') {
      return wx.showToast({ title: '已作废的单据不能修改，可“复制为新单”', icon: 'none' })
    }
    this.setData({ mode })
    if (mode === 'edit' && !this.data.searchKeyword) this.searchProducts('')
  },

  toggleHeader() { this.setData({ headerOpen: !this.data.headerOpen }) },

  toggleReviewOnly() { this.setData({ reviewOnly: !this.data.reviewOnly }) },

  toggleLine(event) {
    const index = Number(event.currentTarget.dataset.index)
    const target = this.data.lines[index]
    if (!target) return
    const open = !target.expanded
    const lines = this.data.lines.map((line, lineIndex) => ({
      ...line,
      expanded: lineIndex === index ? open : false,
      nameFocus: false, quantityFocus: false, priceFocus: false
    }))
    this.setData({ lines, activeLineIndex: null })
  },

  changeForm(event) {
    const field = event.currentTarget.dataset.field
    this.updateDraft({ [`form.${field}`]: event.detail.value, dirty: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '有修改尚未保存' }, () => {
      if (field === 'customerName' && !this.data.form.ledgerId) this.syncLedgerIndex(true)
    })
  },

  changeDate(event) {
    this.updateDraft({ 'form.date': event.detail.value, dirty: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '有修改尚未保存' })
  },

  changeLine(event) {
    const index = Number(event.currentTarget.dataset.index)
    const field = event.currentTarget.dataset.field
    const lines = this.data.lines.slice()
    const learnIdentity = ['name', 'specification', 'unit'].includes(field) && String(event.detail.value || '').trim()
    lines[index] = { ...lines[index], [field]: event.detail.value, remember: learnIdentity ? true : lines[index].remember }
    this.updateDraft({ lines, dirty: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '有修改尚未保存' }, () => this.recalculate())
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
      expanded: lineIndex === index,
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
    this.updateDraft({ lines, dirty: true, statusText: '有修改尚未保存' }, () => this.recalculate())
  },

  toggleRemember(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], remember: event.detail.value }
    this.updateDraft({ lines, dirty: true, statusText: '有修改尚未保存' })
  },

  toggleLineRemark(event) {
    const index = Number(event.currentTarget.dataset.index)
    const lines = this.data.lines.slice()
    lines[index] = { ...lines[index], remarkOpen: !lines[index].remarkOpen }
    this.setData({ lines })
  },

  addLine() {
    const index = this.data.lines.length
    this.updateDraft({ lines: this.data.lines.concat(lineView(blankLine(), index)), dirty: true }, () => {
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
    this.updateDraft({ lines: lines.map(lineView), dirty: true, statusText: '已复制商品，请填写数量' }, () => {
      this.recalculate()
      this.focusLineField(index + 1, 'quantity')
    })
  },

  removeLine(event) {
    if (this.data.lines.length === 1) return wx.showToast({ title: '至少保留一行商品', icon: 'none' })
    const remove = Number(event.currentTarget.dataset.index)
    this.updateDraft({ lines: this.data.lines.filter((_, index) => index !== remove).map(lineView), dirty: true }, () => this.recalculate())
  },

  recalculate() {
    let total = 0
    let incompleteCount = 0
    let reviewCount = 0
    let reviewLineCount = 0
    const lines = this.data.lines.map((line, index) => {
      const next = lineView(line, index)
      const q = decimal(next.quantity)
      const p = decimal(next.price)
      if (q !== null && p !== null) total += q * p
      if (!next.name.trim() || !validUnit(next.unit) || q === null || q <= 0 || p === null || p < 0) incompleteCount += 1
      reviewCount += [next.nameUncertain, next.unitUncertain, next.quantityUncertain, next.priceUncertain].filter(Boolean).length
      if (next.needsReview) reviewLineCount += 1
      return next
    })
    this.setData({ lines, totalText: money(total), incompleteCount, reviewCount, reviewLineCount, reviewOnly: reviewLineCount ? this.data.reviewOnly : false })
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
      let rows = candidates || []
      if (this.data.form.documentType === 'PROCUREMENT') {
        const factory = this.data.form.customerName.trim()
        rows = rows.filter((item) => !factory || !item.factory || item.factory === factory).map((item) => ({
          ...item, price: item.costPrice === undefined ? null : item.costPrice,
          source: item.sourceType === 'PRODUCT' ? ((item.factory || '商品库') + ' · 进价参考') : (item.source + ' · 请填写进价')
        }))
      }
      this.setData({ candidates: rows, candidateTitle: keyword.trim() ? '匹配商品' : '最近常用' })
    }).catch(() => {
      if (requestId === this.searchRequestId) this.setData({ candidates: [] })
    }).finally(() => {
      if (requestId === this.searchRequestId) this.setData({ searching: false })
    })
  },

  chooseCandidate(event) {
    const index = Number(event.currentTarget.dataset.index)
    const candidateKey = String(event.currentTarget.dataset.key || '')
    const candidate = (candidateKey && this.data.candidates.find((item) => item.candidateKey === candidateKey)) || this.data.candidates[index]
    if (!candidate) return
    const requestedTarget = this.data.activeLineIndex === null || this.data.activeLineIndex === undefined
      ? -1 : Number(this.data.activeLineIndex)
    const target = Number.isInteger(requestedTarget) && requestedTarget >= 0 && requestedTarget < this.data.lines.length
      ? requestedTarget : this.data.lines.findIndex((line) => !line.name.trim())
    const existing = target >= 0 ? this.data.lines[target] : blankLine()
    const unitCandidates = Array.isArray(candidate.unitCandidates) ? candidate.unitCandidates.filter(Boolean) : []
    const resolvedUnit = candidate.unit || (unitCandidates.length === 1 ? unitCandidates[0] : existing.unit) || ''
    const selectedPrice = this.data.form.documentType === 'PROCUREMENT' ? candidate.costPrice : candidate.price
    const line = lineView({
      ...existing, name: candidate.name || existing.name || '', specification: candidate.specification || existing.specification || '',
      unit: resolvedUnit, price: selectedPrice === null || selectedPrice === undefined ? existing.price || '' : selectedPrice,
      quantity: existing.quantity || '1', alias: candidate.alias || '', candidateKey: candidate.candidateKey || '',
      referenceSource: candidate.source || '', referenceCategory: candidate.category || '',
      referenceAliases: candidate.aliases || [], unitCandidates,
      referenceCaution: candidate.caution || '', remember: true
    }, target >= 0 ? target : this.data.lines.length)
    const lines = this.data.lines.slice()
    if (target >= 0) lines[target] = line
    else lines.push(line)
    this.updateDraft({ lines, searchKeyword: '', candidates: [], activeLineIndex: null, dirty: true, statusText: '名称、规格、单位和已有价格已自动带入' }, () => {
      this.recalculate()
      this.focusLineField(target >= 0 ? target : lines.length - 1, 'quantity')
    })
  },

  // 开单时新遇到的商品：一键加入商品库，以后在商品里能查到价格和库存。
  addLineToCatalog(event) {
    const index = Number(event.currentTarget.dataset.index)
    const line = this.data.lines[index]
    if (!line || !line.name.trim() || this.addingProduct) return
    if (!validUnit(line.unit)) return wx.showToast({ title: '先填上单位再加入商品库', icon: 'none' })
    const price = decimal(line.price)
    this.addingProduct = true
    api.saveProduct({
      name: line.name.trim(), spec: line.specification.trim(), unit: line.unit.trim(), barcode: '',
      retailPrice: price === null ? 0 : price, wholesalePrice: 0, oldCustomerPrice: 0, costPrice: 0,
      stock: 0, status: 1, locationId: '', sourceFactory: '', imageUrl: ''
    }).then((product) => {
      const lines = this.data.lines.slice()
      lines[index] = lineView({ ...lines[index], candidateKey: `product:${product.id}`, referenceSource: '商品库' }, index)
      this.updateDraft({ lines, dirty: true })
      wx.showToast({ title: '已加入商品库', icon: 'success' })
    }).catch((error) => wx.showToast({ title: errorText(error, '加入失败'), icon: 'none', duration: 3000 }))
      .finally(() => { this.addingProduct = false })
  },

  toggleEvidence() { this.setData({ evidenceOpen: !this.data.evidenceOpen }) },

  payload() {
    return {
      ...this.data.form,
      version: this.data.document ? this.data.document.version : null,
      requestKey: this.data.document ? null : this.newRequestKey,
      lines: this.data.lines.map((line) => ({
        ...line,
        index: undefined, amountText: undefined, unitChoices: undefined,
        nameFocus: undefined, quantityFocus: undefined, priceFocus: undefined,
        nameUncertain: undefined, unitUncertain: undefined, quantityUncertain: undefined,
        priceUncertain: undefined, amountMismatch: undefined, remarkOpen: undefined, remarkToggleText: undefined,
        needsReview: undefined, expanded: undefined, inCatalog: undefined,
        name: line.name.trim(), specification: line.specification.trim(), unit: line.unit.trim(),
        quantity: line.quantity === '' ? null : line.quantity,
        price: line.price === '' ? null : line.price
      }))
    }
  },

  saveDraft() {
    if (this.data.busy) return Promise.resolve(null)
    const payload = this.payload()
    const submitted = JSON.stringify(payload)
    this.setData({ busy: true, actionError: '', actionRetry: '', actionRetryText: '', statusText: '正在保存…' })
    const task = this.data.documentId
      ? api.saveBillingDocument(this.data.documentId, payload)
      : api.createBillingDocument(payload)
    return task.then((document) => {
      const editedWhileSaving = JSON.stringify(this.payload()) !== submitted
      localDraft.remove(this.localStorageKey)
      this.localStorageKey = localDraft.key(document.id)
      if (editedWhileSaving) {
        this.updateDraft({ document, documentId: document.id, dirty: true, statusText: '保存期间有新修改，已暂存，请再次保存' })
        wx.showToast({ title: '新修改已保留，请再次保存', icon: 'none' })
        return null
      }
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
      // 直接展开第一行未补齐的商品，省去用户自己找。
      const first = this.data.lines.findIndex((line) => !line.name.trim() || !validUnit(line.unit)
        || decimal(line.quantity) === null || decimal(line.quantity) <= 0 || decimal(line.price) === null)
      if (this.data.mode !== 'edit') this.setData({ mode: 'edit' })
      if (first >= 0) this.focusLineField(first, decimal(this.data.lines[first].quantity) === null ? 'quantity' : (decimal(this.data.lines[first].price) === null ? 'price' : 'name'))
      this.setData({ actionError: message, actionRetry: '', actionRetryText: '' })
      return wx.showToast({ title: message, icon: 'none', duration: 3000 })
    }
    if (this.data.form.documentType === 'PROCUREMENT' && !this.data.form.customerName.trim()) {
      this.setData({ headerOpen: true, mode: 'edit', actionError: '请填写源头厂商，进货会记入这家厂商的账本' })
      return wx.showToast({ title: '请填写源头厂商', icon: 'none' })
    }
    this.setData({ actionError: '', actionRetry: '', actionRetryText: '' })
    // 底部“保存并导出”就是唯一确认，不再弹二次确认框；单据不登记销售、欠款或库存。
    return Promise.resolve().then(() => {
      return this.saveDraft().then((draft) => {
        if (!draft) return
        this.setData({ busy: true, statusText: '正在保存正式单据…' })
        return api.confirmBillingDocument(draft.id, draft.version).then((document) => {
          this.showDocument(document, true)
          // 选了厂家账本就顺手记账；记账失败不影响单据，可以重试。
          const posting = this.data.form.documentType !== 'PROCUREMENT' && this.data.form.ledgerId ? this.postToLedger(document.id) : Promise.resolve(null)
          return posting.then(() => this.chooseExportFormat(document.id))
        }).catch((error) => {
          const message = errorText(error, '单据保存失败')
          this.setData({ actionError: `单据没有确认：${message}`, actionRetry: '', actionRetryText: '', statusText: '正式单据保存未完成' })
          wx.showToast({ title: message, icon: 'none', duration: 3000 })
        })
          .finally(() => this.setData({ busy: false }))
      })
    })
  },

  chooseExportFormat(documentId) {
    return wx.showActionSheet({
      itemList: ['保存图片到相册', '导出 Excel 文件']
    }).then((choice) => {
      if (choice.tapIndex === 0) return this.saveAsImage()
      if (choice.tapIndex === 1) return this.exportExcel(documentId)
      return null
    }).catch((error) => {
      if (error && /cancel/i.test(error.errMsg || error.message || '')) return null
      const message = errorText(error, '请选择导出格式')
      this.setData({ actionError: message, statusText: '尚未选择导出格式' })
      return null
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
      .then((result) => {
        if (result && result.mode === 'preview') {
          const privacyMissing = /privacy agreement|api scope is not declared/i.test(result.originalError || '')
          this.setData({
            statusText: privacyMissing
              ? '图片已生成；配置小程序相册隐私声明后可直接保存'
              : '图片已生成并打开预览，请在微信真机中保存到相册'
          })
          wx.showToast({ title: privacyMissing ? '图片已生成，请先配置相册声明' : '图片已打开预览', icon: 'none', duration: 3000 })
          return
        }
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
    if (this.data.actionRetry === 'ledger') return this.postToLedger()
  }
})
