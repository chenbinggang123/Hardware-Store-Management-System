const api = require('../../utils/api')
const { showToast } = require('../../utils/router')
const today = () => new Date().toISOString().slice(0, 10)
Page({
  data: { direction: 'RECEIVABLE', customers: [], suppliers: [], parties: [], partyIndex: -1, date: today(), amount: '', method: '', note: '', loading: true, saving: false },
  onLoad(options) { const direction = options.direction === 'PAYABLE' ? 'PAYABLE' : 'RECEIVABLE'; this.setData({ direction }); Promise.all([api.getCustomers(), api.getSuppliers()]).then(([customers, suppliers]) => this.setData({ customers: customers || [], suppliers: suppliers || [], parties: direction === 'RECEIVABLE' ? (customers || []) : (suppliers || []) })).catch(() => showToast('客户和厂商加载失败')).finally(() => this.setData({ loading: false })) },
  selectDirection(e) { const direction = e.currentTarget.dataset.value; this.setData({ direction, parties: direction === 'RECEIVABLE' ? this.data.customers : this.data.suppliers, partyIndex: -1 }) },
  party(e) { this.setData({ partyIndex: Number(e.detail.value) }) },
  date(e) { this.setData({ date: e.detail.value }) },
  input(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  save() { if (this.data.saving) return; const party = this.data.parties[this.data.partyIndex]; const amount = Number(this.data.amount); if (!party) return showToast(`请选择${this.data.direction === 'RECEIVABLE' ? '客户' : '厂商'}`); if (!(amount > 0)) return showToast('请输入正确的金额'); this.setData({ saving: true }); api.recordCounterpartyPayment({ direction: this.data.direction, counterpartyId: party.id, amount, paymentDate: this.data.date, paymentMethod: this.data.method.trim(), note: this.data.note.trim() }).then(result => { const rest = Number(result.unallocatedAmount || 0); if (rest > 0) return wx.showModal({ title: '收付款已保存', content: `已自动冲抵 ${result.documentCount || 0} 张单据，尚有 ¥${rest.toFixed(2)} 未分配，将作为预收/预付款保留。`, showCancel: false }).then(() => wx.navigateBack()); showToast(`已冲抵 ${result.documentCount || 0} 张单据`, 'success'); setTimeout(() => wx.navigateBack(), 450) }).catch(error => showToast((error && (error.message || error.errMsg)) || '登记失败')).finally(() => this.setData({ saving: false })) }
})
