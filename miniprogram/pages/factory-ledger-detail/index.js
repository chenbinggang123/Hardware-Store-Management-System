const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

function documentsOf(ledger) {
  return (ledger.transactions || []).filter(item => !item.reversed && !item.isReversal)
    .map(item => ({ ...item,
      title: item.type === 'RETURN' ? '退货单' : item.type === 'PAYMENT' ? '收款记录' : '拿货单',
      detailItems: (item.items || []).map(row => ({ ...row, priceText: (Number(row.unitPriceCents || 0) / 100).toFixed(2) }))
    }))
    .sort((a, b) => String(b.date).localeCompare(String(a.date)) || String(b.createdAt || '').localeCompare(String(a.createdAt || '')) || Number(b.id) - Number(a.id))
}
Page({
  data: { id: '', month: ledgerStore.currentMonth(), ledger: null, documents: [], visibleDocuments: [], type: '', expandedId: null, summaryOpen: false, loading: true, error: '',
    filters: [{value: '', label: '全部'}, {value: 'PURCHASE', label: '拿货单'}, {value: 'RETURN', label: '退货单'}, {value: 'PAYMENT', label: '收款'}] },
  onLoad(options) { this.setData({ id: options.id || '', month: options.month || ledgerStore.currentMonth() }) },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onMonthChange(event) { this.setData({ month: event.detail.value, expandedId: null }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedger(this.data.id, this.data.month).then(ledger => {
      if (!ledger) throw new Error('账本不存在')
      const documents = documentsOf(ledger)
      this.setData({ ledger, documents, visibleDocuments: documents.filter(item => !this.data.type || item.type === this.data.type), loading: false })
    }).catch(error => this.setData({ loading: false, error: error.message || '账本加载失败，请重试' }))
  },
  filterDocuments(event) {
    const type = event.currentTarget.dataset.type || ''
    this.setData({ type, expandedId: null, visibleDocuments: this.data.documents.filter(item => !type || item.type === type) })
  },
  toggleDocument(event) { const id = event.currentTarget.dataset.id; this.setData({ expandedId: this.data.expandedId === id ? null : id }) },
  toggleSummary() { this.setData({ summaryOpen: !this.data.summaryOpen }) },
  openDocument(event) { const id = event.currentTarget.dataset.id; if (id) openPage('/pages/billing-editor/index', { id }) },
  addPurchase() { openPage('/pages/billing-editor/index', { ledgerId: this.data.id }) },
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
