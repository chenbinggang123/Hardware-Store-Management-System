const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')
const sourcePhotos = require('../../utils/source-photo')

const CUSTOMER_FILTERS = [{ value: '', label: '全部' }, { value: 'PURCHASE', label: '拿货单' }, { value: 'RETURN', label: '退货单' }, { value: 'PAYMENT', label: '收款' }]
const SUPPLIER_FILTERS = [{ value: '', label: '全部' }, { value: 'PURCHASE', label: '进货单' }, { value: 'PAYMENT', label: '付款' }]

function titleOf(type, supplier) {
  if (type === 'PAYMENT') return supplier ? '付款记录' : '收款记录'
  if (type === 'RETURN') return '退货单'
  return supplier ? '进货单' : '拿货单'
}

function documentsOf(ledger) {
  const supplier = ledger.kind === 'SUPPLIER'
  return (ledger.transactions || []).filter(item => !item.reversed && !item.isReversal)
    .map(item => ({ ...item,
      title: titleOf(item.type, supplier),
      detailItems: (item.items || []).map(row => ({ ...row, priceText: (Number(row.unitPriceCents || 0) / 100).toFixed(2) }))
    }))
    .sort((a, b) => String(b.date).localeCompare(String(a.date)) || String(b.createdAt || '').localeCompare(String(a.createdAt || '')) || Number(b.id) - Number(a.id))
}
Page({
  data: { id: '', month: ledgerStore.currentMonth(), ledger: null, supplier: false, documents: [], visibleDocuments: [], type: '', expandedId: null, summaryOpen: false, loading: true, error: '',
    filters: CUSTOMER_FILTERS, filterNames: CUSTOMER_FILTERS.map(item => item.label), filterIndex: 0 },
  onLoad(options) { this.targetTransactionNo = options.transactionNo; this.setData({ id: options.id || '', month: options.month || ledgerStore.currentMonth() }) },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onMonthChange(event) { this.setData({ month: event.detail.value, expandedId: null }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedger(this.data.id, this.data.month).then(ledger => {
      if (!ledger) throw new Error('账本不存在')
      const documents = documentsOf(ledger)
      const target = this.targetTransactionNo && documents.find(item => item.transactionNo === this.targetTransactionNo)
      if (target) { this.setData({ expandedId: target.id }); this.targetTransactionNo = null }
      const supplier = ledger.kind === 'SUPPLIER'
      if (supplier) wx.setNavigationBarTitle({ title: '进货账本' })
      const filters = supplier ? SUPPLIER_FILTERS : CUSTOMER_FILTERS
      this.setData({ ledger, supplier, filters, filterNames: filters.map(item => item.label), filterIndex: Math.max(0, filters.findIndex(item => item.value === this.data.type)), documents, visibleDocuments: documents.filter(item => !this.data.type || item.type === this.data.type), loading: false })
      this.loadPhotos(documents)
    }).catch(error => this.setData({ loading: false, error: error.message || '账本加载失败，请重试' }))
  },
  // 下拉选择单据类型（按序号）
  pickFilter(event) {
    const item = this.data.filters[Number(event.detail.value)]
    this.filterDocuments({ currentTarget: { dataset: { type: item ? item.value : '' } } })
  },
  // 单据原图：拍照开单保存过照片的单据，展开后可以直接看原图
  loadPhotos(documents) {
    const ids = documents.map(item => item.sourcePhoto && item.sourcePhoto.cloudFileId).filter(Boolean)
    if (!ids.length) return
    sourcePhotos.tempUrls(ids).then(urls => {
      const withPhoto = this.data.documents.map(item => ({ ...item, photoUrl: item.sourcePhoto ? urls[item.sourcePhoto.cloudFileId] || '' : '' }))
      this.setData({ documents: withPhoto, visibleDocuments: withPhoto.filter(item => !this.data.type || item.type === this.data.type) })
    })
  },
  previewPhoto(event) {
    const url = event.currentTarget.dataset.url
    sourcePhotos.preview(url, this.data.visibleDocuments.map(item => item.photoUrl).filter(Boolean))
  },
  filterDocuments(event) {
    const type = event.currentTarget.dataset.type || ''
    this.setData({ type, filterIndex: Math.max(0, this.data.filters.findIndex(item => item.value === type)), expandedId: null, visibleDocuments: this.data.documents.filter(item => !type || item.type === type) })
  },
  toggleDocument(event) { const id = event.currentTarget.dataset.id; this.setData({ expandedId: this.data.expandedId === id ? null : id }) },
  toggleSummary() { this.setData({ summaryOpen: !this.data.summaryOpen }) },
  openDocument(event) { const id = event.currentTarget.dataset.id; if (id) openPage('/pages/billing-editor/index', { id }) },
  addPurchase() {
    // 进货的账本：开采购单，源头厂商自动填好，保存后加库存并记到这本账。
    if (this.data.supplier) openPage('/pages/billing-editor/index', { documentType: 'PROCUREMENT', factory: this.data.ledger.factoryName })
    else openPage('/pages/billing-editor/index', { ledgerId: this.data.id })
  },
  openProducts() { openPage('/pages/products/index', { factory: this.data.ledger.factoryName }) },
  addPayment() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'PAYMENT' }) },
  editLedger() { openPage('/pages/factory-ledger-form/index', { id: this.data.id }) },
  deleteDocument(event) {
    const id = event.currentTarget.dataset.id
    const item = this.data.documents.find(row => Number(row.id) === Number(id))
    if (!item || this.deleting) return
    wx.showModal({ title: `删除这张${item.title}？`, content: '删除后不再显示，对应账款会重新计算。', confirmText: '删除', confirmColor: '#A83F32' }).then(choice => {
      if (!choice.confirm) return
      this.deleting = true
      const action = item.source === 'BILLING' ? api.deleteLedgerBillingDocument(this.data.id, id)
        : api.reverseFactoryLedgerTransaction(this.data.id, id, '用户删除错单')
      return action.then(() => { showToast('已删除', 'success'); this.setData({ expandedId: null }); return this.loadData() })
        .catch(error => showToast(error.message || '删除失败，请重试'))
        .finally(() => { this.deleting = false })
    })
  }
})
