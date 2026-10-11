const STORAGE_KEY = 'factory_ledger_state_v1'

const SETTLEMENT_STATUS = [
  { value: 'PENDING_SUMMARY', label: '待汇总' },
  { value: 'PENDING_CHECK', label: '待核对' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'INVOICED', label: '已开单' },
  { value: 'PARTIAL_PAID', label: '部分付款' },
  { value: 'PAID', label: '已付款' },
  { value: 'CLOSED', label: '已结清' }
]

function emptyState() {
  return { ledgers: [], transactions: [], settlements: {} }
}

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function readState() {
  const saved = wx.getStorageSync(STORAGE_KEY)
  if (!saved || typeof saved !== 'object') return emptyState()
  return {
    ledgers: Array.isArray(saved.ledgers) ? saved.ledgers : [],
    transactions: Array.isArray(saved.transactions) ? saved.transactions : [],
    settlements: saved.settlements && typeof saved.settlements === 'object' ? saved.settlements : {}
  }
}

function writeState(state) {
  wx.setStorageSync(STORAGE_KEY, state)
}

function toCents(value) {
  const number = Number(value)
  if (!Number.isFinite(number)) return 0
  return Math.round(number * 100)
}

function yuan(cents) {
  return (Number(cents || 0) / 100).toFixed(2)
}

function monthOf(date) {
  return String(date || '').slice(0, 7)
}

function today() {
  const date = new Date()
  const pad = (value) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

function currentMonth() {
  return today().slice(0, 7)
}

function id(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

function ledgerNumber(state) {
  const date = today().replace(/-/g, '')
  const serial = String(state.ledgers.length + 1).padStart(4, '0')
  return `ZB-${date}-${serial}`
}

function statusLabel(value) {
  const found = SETTLEMENT_STATUS.find((item) => item.value === value)
  return found ? found.label : SETTLEMENT_STATUS[0].label
}

function transactionDelta(transaction) {
  if (transaction.type === 'PURCHASE') return transaction.amountCents
  if (transaction.type === 'RETURN' || transaction.type === 'PAYMENT') return -transaction.amountCents
  return transaction.amountCents
}

function transactionLabel(type) {
  return ({ PURCHASE: '拿货', RETURN: '退货', PAYMENT: '收款' })[type] || '调整'
}

function summarize(ledger, transactions, month, settlement) {
  const before = transactions.filter((item) => item.date < `${month}-01`)
  const current = transactions.filter((item) => monthOf(item.date) === month)
  const openingCents = Number(ledger.openingBalanceCents || 0) + before.reduce((sum, item) => sum + transactionDelta(item), 0)
  const purchaseCents = current.filter((item) => item.type === 'PURCHASE').reduce((sum, item) => sum + item.amountCents, 0)
  const returnCents = current.filter((item) => item.type === 'RETURN').reduce((sum, item) => sum + item.amountCents, 0)
  const paymentCents = current.filter((item) => item.type === 'PAYMENT').reduce((sum, item) => sum + item.amountCents, 0)
  const balanceCents = openingCents + purchaseCents - returnCents - paymentCents
  const status = settlement || 'PENDING_SUMMARY'
  return {
    openingCents, purchaseCents, returnCents, paymentCents, balanceCents,
    openingAmount: yuan(openingCents), purchaseAmount: yuan(purchaseCents), returnAmount: yuan(returnCents),
    paymentAmount: yuan(paymentCents), balanceAmount: yuan(balanceCents),
    purchaseCount: current.filter((item) => item.type === 'PURCHASE').length,
    transactionCount: current.length,
    status,
    statusLabel: statusLabel(status)
  }
}

function listLedgers(options = {}) {
  const state = readState()
  const month = options.month || currentMonth()
  const keyword = String(options.keyword || '').trim().toLowerCase()
  const ledgers = state.ledgers
    .filter((ledger) => !keyword || [ledger.factoryName, ledger.contactPerson, ledger.phone, ledger.ledgerNo].some((value) => String(value || '').toLowerCase().includes(keyword)))
    .map((ledger) => {
      const transactions = state.transactions.filter((item) => item.ledgerId === ledger.id)
      const summary = summarize(ledger, transactions, month, state.settlements[`${ledger.id}:${month}`])
      return { ...clone(ledger), ...summary }
    })
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  return {
    month,
    ledgers,
    totals: {
      factoryCount: ledgers.length,
      purchaseAmount: yuan(ledgers.reduce((sum, item) => sum + item.purchaseCents, 0)),
      balanceAmount: yuan(ledgers.reduce((sum, item) => sum + item.balanceCents, 0)),
      pendingCount: ledgers.filter((item) => !['PAID', 'CLOSED'].includes(item.status)).length
    }
  }
}

function getLedger(ledgerId, month = currentMonth()) {
  const state = readState()
  const ledger = state.ledgers.find((item) => item.id === ledgerId)
  if (!ledger) return null
  const all = state.transactions.filter((item) => item.ledgerId === ledgerId)
  const transactions = all
    .filter((item) => monthOf(item.date) === month)
    .sort((a, b) => `${b.date}${b.createdAt}`.localeCompare(`${a.date}${a.createdAt}`))
    .map((item) => ({
      ...clone(item),
      typeLabel: transactionLabel(item.type),
      amount: yuan(item.amountCents),
      signedAmount: `${item.type === 'PURCHASE' ? '+' : '-'}¥${yuan(item.amountCents)}`,
      content: item.type === 'PAYMENT'
        ? '登记收款'
        : item.items.map((line) => `${line.productName}${line.specification ? ` ${line.specification}` : ''} × ${line.quantity}`).join('；')
    }))
  return { ...clone(ledger), month, transactions, summary: summarize(ledger, all, month, state.settlements[`${ledger.id}:${month}`]) }
}

function createLedger(payload) {
  const state = readState()
  const factoryName = String(payload.factoryName || '').trim()
  if (!factoryName) throw new Error('请填写工厂名称')
  if (state.ledgers.some((item) => item.factoryName === factoryName)) throw new Error('这家工厂已经有账本')
  const now = new Date().toISOString()
  const ledger = {
    id: id('ledger'), ledgerNo: ledgerNumber(state), factoryName,
    contactPerson: String(payload.contactPerson || '').trim(), phone: String(payload.phone || '').trim(),
    startDate: payload.startDate || today(), settlementDay: Math.max(1, Math.min(31, Number(payload.settlementDay) || 31)),
    openingBalanceCents: toCents(payload.openingBalance), remark: String(payload.remark || '').trim(),
    createdAt: now, updatedAt: now
  }
  state.ledgers.unshift(ledger)
  writeState(state)
  return clone(ledger)
}

function addTransaction(ledgerId, payload) {
  const state = readState()
  const ledger = state.ledgers.find((item) => item.id === ledgerId)
  if (!ledger) throw new Error('账本不存在')
  const type = payload.type || 'PURCHASE'
  const receiptNo = String(payload.receiptNo || '').trim()
  if (receiptNo && state.transactions.some((item) => item.ledgerId === ledgerId && item.receiptNo === receiptNo)) throw new Error('这张收据已经记过账')
  const items = (payload.items || []).map((item) => ({
    productName: String(item.productName || '').trim(), specification: String(item.specification || '').trim(),
    unit: String(item.unit || '件').trim(), quantity: Number(item.quantity) || 0, unitPriceCents: toCents(item.unitPrice)
  })).filter((item) => item.productName && item.quantity > 0 && item.unitPriceCents >= 0)
  const amountCents = type === 'PAYMENT'
    ? toCents(payload.paymentAmount)
    : items.reduce((sum, item) => sum + Math.round(item.quantity * item.unitPriceCents), 0)
  if (amountCents <= 0) throw new Error(type === 'PAYMENT' ? '请填写收款金额' : '请至少填写一件有效商品')
  const now = new Date().toISOString()
  const transaction = {
    id: id('txn'), transactionNo: `LS-${Date.now()}`, ledgerId, type, date: payload.date || today(),
    receiptNo, items, amountCents, remark: String(payload.remark || '').trim(), source: 'MINIPROGRAM', createdAt: now
  }
  state.transactions.unshift(transaction)
  ledger.updatedAt = now
  const month = monthOf(transaction.date)
  state.settlements[`${ledgerId}:${month}`] = 'PENDING_SUMMARY'
  writeState(state)
  return clone(transaction)
}

function setSettlementStatus(ledgerId, month, status) {
  if (!SETTLEMENT_STATUS.some((item) => item.value === status)) throw new Error('无效的结账状态')
  const state = readState()
  state.settlements[`${ledgerId}:${month}`] = status
  writeState(state)
}

module.exports = {
  SETTLEMENT_STATUS, today, currentMonth, yuan, listLedgers, getLedger, createLedger, addTransaction, setSettlementStatus
}
