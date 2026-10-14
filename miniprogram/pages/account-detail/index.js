const api = require('../../utils/api')
const { showToast } = require('../../utils/router')
const today = () => new Date().toISOString().slice(0, 10)
const money = value => Number(value || 0).toFixed(2)
Page({
  data: { id: '', document: null, payments: [], amount: '', date: today(), methodIndex: 0, methods: ['微信', '支付宝', '现金', '银行卡', '其他'], note: '', loading: true, error: '', saving: false },
  onLoad(options) { this.setData({ id: options.id }); this.load() },
  load() { this.setData({ loading: true, error: '' }); return api.getAccountDocument(this.data.id).then(result => { const d = result.document; const overdue = d.status !== 'SETTLED' && d.dueDate && d.dueDate < today(); this.setData({ document: { ...d, directionText: d.direction === 'RECEIVABLE' ? '应收' : '应付', totalText: money(d.totalAmount), settledText: money(d.settledAmount), outstandingText: money(d.outstandingAmount), statusText: overdue ? '已逾期' : ({ UNPAID: '未收付', PARTIAL: '部分收付', SETTLED: '已结清' })[d.status], overdue }, payments: (result.payments || []).map(item => ({ ...item, amountText: money(item.amount) })) }) }).catch(error => this.setData({ error: (error && (error.message || error.errMsg)) || '账款加载失败' })).finally(() => this.setData({ loading: false })) },
  input(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) }, date(e) { this.setData({ date: e.detail.value }) }, method(e) { this.setData({ methodIndex: Number(e.detail.value) }) },
  pay() { const amount = Number(this.data.amount); if (!(amount > 0)) return showToast('请输入本次收付金额'); if (amount > Number(this.data.document.outstandingAmount)) return showToast('金额不能超过剩余欠款'); this.setData({ saving: true }); api.recordAccountPayment(this.data.id, { amount, paymentDate: this.data.date, paymentMethod: this.data.methods[this.data.methodIndex], note: this.data.note.trim() }).then(() => { showToast('收付款已登记', 'success'); this.setData({ amount: '', note: '' }); return this.load() }).catch(error => showToast((error && (error.message || error.errMsg)) || '登记失败')).finally(() => this.setData({ saving: false })) }
})
