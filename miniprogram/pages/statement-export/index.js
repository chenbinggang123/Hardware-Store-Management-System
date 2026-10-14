const api = require('../../utils/api')
const { fileView, openAgentFile } = require('../../utils/agent-files')
const { showToast } = require('../../utils/router')
const today = () => new Date().toISOString().slice(0, 10)
const monthStart = () => `${today().slice(0, 7)}-01`
Page({
  data: { direction: 'RECEIVABLE', customers: [], suppliers: [], parties: [], partyIndex: -1, dateFrom: monthStart(), dateTo: today(), loading: true, exporting: false },
  onLoad() { Promise.all([api.getCustomers(), api.getSuppliers()]).then(([customers, suppliers]) => this.setData({ customers: customers || [], suppliers: suppliers || [], parties: customers || [] })).catch(() => showToast('客户和厂商加载失败')).finally(() => this.setData({ loading: false })) },
  selectDirection(e) { const direction = e.currentTarget.dataset.value; this.setData({ direction, parties: direction === 'RECEIVABLE' ? this.data.customers : this.data.suppliers, partyIndex: -1 }) },
  party(e) { this.setData({ partyIndex: Number(e.detail.value) }) },
  dateFrom(e) { this.setData({ dateFrom: e.detail.value }) },
  dateTo(e) { this.setData({ dateTo: e.detail.value }) },
  exportFile() { if (this.data.exporting) return; const party = this.data.parties[this.data.partyIndex]; if (!party) return showToast(`请选择${this.data.direction === 'RECEIVABLE' ? '客户' : '厂商'}`); if (this.data.dateFrom > this.data.dateTo) return showToast('开始日期不能晚于结束日期'); this.setData({ exporting: true }); api.exportFinanceStatement({ direction: this.data.direction, counterpartyId: party.id, dateFrom: this.data.dateFrom, dateTo: this.data.dateTo, idempotencyKey: `statement-${Date.now()}-${party.id}` }).then(result => { showToast('对账单已生成', 'success'); return openAgentFile(fileView(result)) }).catch(error => showToast((error && (error.message || error.errMsg)) || '对账单生成失败')).finally(() => this.setData({ exporting: false })) }
})
