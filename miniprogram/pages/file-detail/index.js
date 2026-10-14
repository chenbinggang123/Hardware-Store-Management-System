const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

const PURPOSES = [
  { code: 'CUSTOMER_RECEIPT', label: '客户单据' },
  { code: 'SUPPLIER_RECEIPT', label: '厂商单据' },
  { code: 'PRICE_LIST', label: '商品价格表' },
  { code: 'PRODUCT_IMPORT', label: '商品资料' },
  { code: 'INVENTORY_COUNT', label: '库存盘点' },
  { code: 'UNCLASSIFIED', label: '其他文件' }
]
const RELATED_TYPES = [
  { code: 'NONE', label: '不关联' },
  { code: 'CUSTOMER', label: '客户' },
  { code: 'SUPPLIER', label: '厂商' }
]

function errorText(error, fallback) {
  return (error && (error.message || error.errMsg)) || fallback
}

function timeText(value) {
  if (!value) return '时间未知'
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(0, 16) : text
}

Page({
  data: {
    kind: '', id: '', item: null, activities: [],
    purposes: PURPOSES, purposeIndex: 5,
    relatedTypes: RELATED_TYPES, relatedTypeIndex: 0,
    customers: [], suppliers: [], parties: [], partyIndex: -1,
    note: '', relatedId: null, canCreateAccount: false, accountDirectionText: '', accountDraft: null, loading: true, loadError: '', saving: false
  },

  onLoad(options) {
    this.setData({ kind: String(options.kind || '').toUpperCase(), id: options.id || '' })
    this.loadDetail()
  },

  loadDetail() {
    this.setData({ loading: true, loadError: '' })
    return Promise.all([
      api.getFileCenterDetail(this.data.kind, this.data.id),
      api.getCustomers(),
      api.getSuppliers()
    ]).then(([detail, customers, suppliers]) => {
      const purposeIndex = Math.max(PURPOSES.findIndex(item => item.code === detail.purposeCode), 0)
      const relatedTypeIndex = Math.max(RELATED_TYPES.findIndex(item => item.code === detail.relatedType), 0)
      const customerList = customers || []
      const supplierList = suppliers || []
      const parties = detail.relatedType === 'CUSTOMER' ? customerList : detail.relatedType === 'SUPPLIER' ? supplierList : []
      const partyIndex = parties.findIndex(item => Number(item.id) === Number(detail.relatedId))
      this.setData({
        item: detail.item,
        activities: (detail.activities || []).map(item => ({ ...item, timeText: timeText(item.createTime) })),
        purposeIndex, relatedTypeIndex, customers: customerList, suppliers: supplierList,
        parties, partyIndex, note: detail.note || '', relatedId: detail.relatedId,
        canCreateAccount: ['CUSTOMER', 'SUPPLIER'].includes(detail.relatedType),
        accountDirectionText: detail.relatedType === 'CUSTOMER' ? '应收' : '应付',
        accountDraft: detail.accountDraft ? { ...detail.accountDraft, reviewText: (detail.accountDraft.reviewFields || []).join('、') } : null
      })
    }).catch((error) => this.setData({ loadError: errorText(error, '文件资料暂时无法加载') }))
      .finally(() => this.setData({ loading: false }))
  },

  onPurposeChange(event) { this.setData({ purposeIndex: Number(event.detail.value) }) },
  onRelatedTypeChange(event) {
    const relatedTypeIndex = Number(event.detail.value)
    const code = RELATED_TYPES[relatedTypeIndex].code
    this.setData({
      relatedTypeIndex,
      parties: code === 'CUSTOMER' ? this.data.customers : code === 'SUPPLIER' ? this.data.suppliers : [],
      partyIndex: -1
    })
  },
  onPartyChange(event) { this.setData({ partyIndex: Number(event.detail.value) }) },
  onNoteInput(event) { this.setData({ note: event.detail.value }) },
  retryLoad() { this.loadDetail() },

  createAccount() {
    const related = RELATED_TYPES[this.data.relatedTypeIndex]
    const party = this.data.parties[this.data.partyIndex]
    if (!party || related.code === 'NONE') return showToast('请先保存关联客户或厂商')
    openPage('/pages/account-form/index', {
      direction: related.code === 'CUSTOMER' ? 'RECEIVABLE' : 'PAYABLE',
      counterpartyId: party.id, fileSourceKind: this.data.kind, fileSourceId: this.data.id,
      number: this.data.accountDraft && this.data.accountDraft.documentNumber,
      date: this.data.accountDraft && this.data.accountDraft.documentDate,
      total: this.data.accountDraft && this.data.accountDraft.totalAmount,
      settled: this.data.accountDraft && this.data.accountDraft.settledAmount,
      reviewFields: this.data.accountDraft && (this.data.accountDraft.reviewFields || []).join('、')
    })
  },

  save() {
    if (this.data.saving) return
    const purpose = PURPOSES[this.data.purposeIndex]
    const related = RELATED_TYPES[this.data.relatedTypeIndex]
    const party = this.data.parties[this.data.partyIndex]
    if (related.code !== 'NONE' && !party) return showToast(`请选择${related.label}`)
    this.setData({ saving: true })
    api.organizeFile(this.data.kind, this.data.id, {
      purposeCode: purpose.code,
      relatedType: related.code,
      relatedId: party ? party.id : null,
      relatedName: party ? party.name : null,
      note: String(this.data.note || '').trim()
    }).then(() => {
      showToast('文件资料已保存', 'success')
      setTimeout(() => wx.navigateBack(), 350)
    }).catch((error) => showToast(errorText(error, '保存失败，请重试')))
      .finally(() => this.setData({ saving: false }))
  }
})
