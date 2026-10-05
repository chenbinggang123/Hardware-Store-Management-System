const api = require('../../utils/api')
const { openPage } = require('../../utils/router')

const TYPE_LABELS = { COST: '厂家成本', PURCHASE: '采购成交价', RETAIL: '零售价', WHOLESALE: '批发价', OLD_CUSTOMER: '老客户价' }

function money(value) {
  if (value === null || value === undefined || value === '') return '未设置'
  return `¥${Number(value).toFixed(2)}`
}

function formatTime(value) {
  if (!value) return '--'
  return String(value).replace('T', ' ').slice(0, 16)
}

function decorate(item) {
  const percent = item.changePercent === null || item.changePercent === undefined ? null : Number(item.changePercent)
  return {
    ...item,
    typeLabel: TYPE_LABELS[item.priceType] || item.priceType || '价格',
    dateText: item.effectiveDate || formatTime(item.createTime),
    timeText: formatTime(item.createTime),
    beforeText: money(item.beforePrice),
    afterText: money(item.afterPrice),
    changeText: percent === null ? '价格已调整' : `${percent > 0 ? '+' : ''}${percent.toFixed(1)}%`,
    direction: percent === null ? 'neutral' : (percent > 0 ? 'up' : percent < 0 ? 'down' : 'neutral'),
    sourceText: item.sourceType === 'EXCEL_SUPPLIER_PRICE' ? `厂家价格表 · 任务 ${item.sourceTaskId}`
      : item.sourceType === 'PURCHASE_STOCK_IN' ? '采购入库记录'
        : item.sourceType === 'MANUAL_PRODUCT_EDIT' ? '手动修改商品' : (item.sourceType || '业务记录'),
    operatorText: item.operatorId ? `操作人 #${item.operatorId}` : '系统记录',
    appliedText: item.appliedToProduct ? '已更新商品当前价格' : '仅记录成交或观察价格'
  }
}

Page({
  data: {
    productId: '', supplierId: '', productName: '', supplierName: '',
    products: [], productNames: [], productIndex: -1,
    filter: 'ALL',
    filters: [
      { key: 'ALL', label: '全部' }, { key: 'COST', label: '厂家成本' },
      { key: 'PURCHASE', label: '采购价' }, { key: 'RETAIL', label: '零售价' },
      { key: 'WHOLESALE', label: '批发价' }, { key: 'OLD_CUSTOMER', label: '老客户价' }
    ],
    history: [], visibleHistory: [], page: 0, hasMore: false,
    loading: true, loadingMore: false, loadError: ''
  },
  onLoad(options) {
    this.setData({ productId: options.id || options.productId || '', supplierId: options.supplierId || '', productName: options.name || '', supplierName: options.supplierName || '' })
    if (this.data.productId || this.data.supplierId) this.loadHistory(true)
    else this.loadProducts()
  },
  loadProducts() {
    this.setData({ loading: true, loadError: '' })
    api.getProducts().then((products) => this.setData({ products: products || [], productNames: (products || []).map((item) => item.name), loading: false }))
      .catch((error) => this.setData({ loading: false, loadError: (error && error.message) || '商品列表没有加载成功' }))
  },
  selectProduct(event) {
    const index = Number(event.detail.value)
    const product = this.data.products[index]
    if (!product) return
    this.setData({ productIndex: index, productId: product.id, productName: product.name, history: [], visibleHistory: [], page: 0 }, () => this.loadHistory(true))
  },
  loadHistory(reset = false) {
    if ((!this.data.productId && !this.data.supplierId) || this.data.loadingMore) return
    const page = reset ? 0 : this.data.page
    this.setData(reset ? { loading: true, loadError: '' } : { loadingMore: true })
    const request = this.data.supplierId
      ? api.getSupplierPriceHistory(this.data.supplierId, this.data.productId, page, 200)
      : api.getProductPriceHistory(this.data.productId, page, 200)
    request.then((result) => {
      const next = (result.items || []).map(decorate)
      const history = reset ? next : this.data.history.concat(next)
      this.setData({ history, page: page + 1, hasMore: page + 1 < Number(result.totalPages || 0) }, () => this.applyFilter())
    }).catch((error) => this.setData({ loadError: (error && error.message) || '价格记录没有加载成功' }))
      .finally(() => this.setData({ loading: false, loadingMore: false }))
  },
  applyFilter() {
    this.setData({ visibleHistory: this.data.filter === 'ALL' ? this.data.history : this.data.history.filter((item) => item.priceType === this.data.filter) })
  },
  changeFilter(event) { this.setData({ filter: event.currentTarget.dataset.key }, () => this.applyFilter()) },
  loadMore() { this.loadHistory(false) },
  retryLoad() { this.data.productId || this.data.supplierId ? this.loadHistory(true) : this.loadProducts() },
  openSourceTask(event) { openPage('/pages/excel-task-detail/index', { id: event.currentTarget.dataset.id }) },
  openProduct(event) { openPage('/pages/product-detail/index', { id: event.currentTarget.dataset.id }) }
})
