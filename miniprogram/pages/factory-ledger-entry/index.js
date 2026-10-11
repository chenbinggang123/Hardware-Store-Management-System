const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')

const TYPES = [
  { value: 'PURCHASE', label: '拿货' },
  { value: 'RETURN', label: '退货' },
  { value: 'PAYMENT', label: '收款' }
]

function blankItem() { return { productName: '', specification: '', unit: '件', quantity: '1', unitPrice: '' } }

Page({
  data: {
    ledgerId: '', ledger: null, types: TYPES, typeNames: TYPES.map((item) => item.label), typeIndex: 0,
    form: { type: 'PURCHASE', date: ledgerStore.today(), receiptNo: '', paymentAmount: '', remark: '', items: [blankItem()] }, saving: false
  },
  onLoad(options) {
    const typeIndex = Math.max(0, TYPES.findIndex((item) => item.value === options.type))
    this.setData({ ledgerId: options.id || '', typeIndex, 'form.type': TYPES[typeIndex].value })
    api.getFactoryLedger(options.id, ledgerStore.currentMonth()).then((ledger) => {
      if (ledger) this.setData({ ledger })
    }).catch(() => wx.showToast({ title: '账本信息加载失败', icon: 'none' }))
  },
  changeType(event) { const index = Number(event.detail.value); this.setData({ typeIndex: index, 'form.type': TYPES[index].value }) },
  changeDate(event) { this.setData({ 'form.date': event.detail.value }) },
  updateField(event) { this.setData({ [`form.${event.currentTarget.dataset.field}`]: event.detail.value }) },
  updateItem(event) { this.setData({ [`form.items[${event.currentTarget.dataset.index}].${event.currentTarget.dataset.field}`]: event.detail.value }) },
  addItem() { this.setData({ 'form.items': this.data.form.items.concat(blankItem()) }) },
  removeItem(event) {
    if (this.data.form.items.length === 1) { wx.showToast({ title: '至少保留一行商品', icon: 'none' }); return }
    const items = this.data.form.items.slice(); items.splice(Number(event.currentTarget.dataset.index), 1); this.setData({ 'form.items': items })
  },
  preview() {
    if (this.data.saving) return
    const form = this.data.form
    const amount = form.type === 'PAYMENT'
      ? Number(form.paymentAmount || 0)
      : form.items.reduce((sum, item) => sum + Number(item.quantity || 0) * Number(item.unitPrice || 0), 0)
    if (!(amount > 0)) { wx.showToast({ title: form.type === 'PAYMENT' ? '请填写收款金额' : '请填写商品、数量和单价', icon: 'none' }); return }
    const typeLabel = TYPES[this.data.typeIndex].label
    wx.showModal({
      title: `确认记入${typeLabel} ¥${amount.toFixed(2)}？`,
      content: `${this.data.ledger ? this.data.ledger.factoryName : ''}\n日期：${form.date}\n收据：${form.receiptNo || '未填写'}\n确认后将更新账本余额。`,
      confirmText: '确认记入',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ saving: true })
        api.addFactoryLedgerTransaction(this.data.ledgerId, form).then(() => {
          wx.showToast({ title: '已经记到账本', icon: 'success' })
          setTimeout(() => wx.navigateBack(), 350)
        }).catch((error) => {
          wx.showToast({ title: error.message || '记账失败', icon: 'none' })
          this.setData({ saving: false })
        })
      }
    })
  }
})
