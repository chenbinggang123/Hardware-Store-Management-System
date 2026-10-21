const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: { id: '', month: ledgerStore.currentMonth(), ledger: null, loading: true, error: '' },
  onLoad(options) { this.setData({ id: options.id || '', month: options.month || ledgerStore.currentMonth() }) },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    return api.getFactoryLedger(this.data.id, this.data.month).then((ledger) => {
      if (!ledger) throw new Error('账本不存在')
      this.setData({ ledger, loading: false })
    }).catch((error) => {
      this.setData({ loading: false, error: (error && error.message) || '账本加载失败，请重试' })
    })
  },
  addPurchase() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'PURCHASE' }) },
  addPayment() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'PAYMENT' }) },
  addReturn() { openPage('/pages/factory-ledger-entry/index', { id: this.data.id, type: 'RETURN' }) },
  editLedger() { openPage('/pages/factory-ledger-form/index', { id: this.data.id }) },

  // 记错了：不删除原记录，而是记一笔金额相反的冲销，应收马上恢复。
  reverseTransaction(event) {
    const transactionId = event.currentTarget.dataset.id
    const label = event.currentTarget.dataset.label || '这笔记录'
    if (!transactionId || this.reversing) return
    wx.showModal({
      title: `冲销${label}？`,
      content: '',
      editable: true,
      placeholderText: '写一句原因，例如数量记错了',
      confirmText: '冲销'
    }).then((choice) => {
      if (!choice.confirm) return
      this.reversing = true
      return api.reverseFactoryLedgerTransaction(this.data.id, transactionId, choice.content || '记错了')
        .then(() => { showToast('已冲销', 'success'); return this.loadData() })
        .catch((error) => showToast((error && error.message) || '冲销失败，请重试'))
        .finally(() => { this.reversing = false })
    })
  }
})
