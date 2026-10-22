const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

// 工厂账本分两种：买的 = 工厂来我们这里拿货（他们欠我们）；进货的 = 我们向源头厂商进货（我们欠他们）。
const KINDS = {
  CUSTOMER: { subtitle: '工厂来拿货，看每家还欠我们多少', dueLabel: '还需付', totalLabel: '总共还需付', creditLabel: '已多付', createLabel: '新建', empty: '还没有“买的”账本', search: '工厂、联系人或电话' },
  SUPPLIER: { subtitle: '我们去进货，看还欠每家厂商多少', dueLabel: '我们还欠', totalLabel: '我们总共还欠', creditLabel: '已多付给他', createLabel: '新建', empty: '还没有进货账本，开一张采购单会自动建立', search: '源头厂商、联系人或电话' }
}

Page({
  data: {
    kind: 'CUSTOMER', copy: KINDS.CUSTOMER, kindNames: ['买的（工厂来拿货）', '进货的（我们去进货）'],
    month: ledgerStore.currentMonth(), keyword: '', ledgers: [],
    totals: { factoryCount: 0, totalDueAmount: '0.00' }, loading: true, error: ''
  },
  onLoad(options = {}) {
    const kind = options.kind === 'SUPPLIER' ? 'SUPPLIER' : 'CUSTOMER'
    this.setData({ kind, copy: KINDS[kind] })
  },
  onShow() { this.loadData() },
  onPullDownRefresh() { this.loadData().finally(() => wx.stopPullDownRefresh()) },
  changeKind(event) {
    const kind = event.currentTarget.dataset.kind === 'SUPPLIER' ? 'SUPPLIER' : 'CUSTOMER'
    if (kind === this.data.kind) return
    this.setData({ kind, copy: KINDS[kind], keyword: '', ledgers: [] }, () => this.loadData())
  },
  pickKind(event) {
    this.changeKind({ currentTarget: { dataset: { kind: Number(event.detail.value) === 1 ? 'SUPPLIER' : 'CUSTOMER' } } })
  },
  onKeywordInput(event) { this.setData({ keyword: event.detail.value }) },
  onMonthChange(event) { this.setData({ month: event.detail.value }, () => this.loadData()) },
  loadData() {
    this.setData({ loading: true, error: '' })
    const kind = this.data.kind
    return api.getFactoryLedgers({ month: this.data.month, keyword: this.data.keyword, kind }).then((result) => {
      if (kind !== this.data.kind) return
      this.setData({ ledgers: result.ledgers || [], totals: result.totals || this.data.totals, loading: false })
    }).catch((error) => {
      this.setData({ loading: false, error: (error && error.message) || '账本加载失败，请重试' })
    })
  },
  openCreate() { openPage('/pages/factory-ledger-form/index', { kind: this.data.kind }) },
  openProcurement() { openPage('/pages/billing-editor/index', { documentType: 'PROCUREMENT' }) },
  emptyAction() { if (this.data.kind === 'SUPPLIER') this.openProcurement(); else this.openCreate() },
  openMonthlySummary() { openPage('/pages/monthly-summary/index', { month: this.data.month }) },
  openDetail(event) { openPage('/pages/factory-ledger-detail/index', { id: event.currentTarget.dataset.id, month: this.data.month }) }
})
