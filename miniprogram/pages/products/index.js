const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

const { modifyQuantity } = require('../../utils/product-quantity')
const PAGE_SIZE = 20
const VIEWS = ['all', 'frequent', 'attention']

function activityText(item) {
  const sold = item.lastSoldAt
  const purchased = item.lastPurchasedAt
  const value = item.lastActivityAt
  if (!value) return '暂无交易记录'
  const date = String(value).slice(0, 10)
  if (!sold && !purchased) return `最近使用 ${date}`
  if (sold && purchased) return `最近交易 ${date}`
  return `${sold ? '最近售出' : '最近进货'} ${date}`
}

function normalizeProduct(item) {
  const name = item.name || '商品'
  const stockStateText = { IN_STOCK: '有货', LOW: '不多', OUT_OF_STOCK: '缺货', UNKNOWN: '未记录' }[item.stockState] || '未记录'
  return {
    ...item,
    name,
    initial: String(name).slice(0, 1),
    barcodeText: item.barcode || '--',
    specText: [item.spec, item.unit].filter(Boolean).join(' · ') || '规格未填写',
    retailPrice: item.retailPrice || 0,
    costPrice: item.costPrice || 0,
    stockStateText,
    stockText: item.inventoryRecorded ? `${Number(item.quantity || 0)} ${item.unit || '件'}` : '未记录',
    locationText: item.locationId || '未设置',
    activityText: activityText(item),
    attentionText: (item.attentionReasons || []).join(' · ')
  }
}

Page({
  data: {
    keyword: '', view: 'all', factory: '', factories: [], factoryNames: ['全部厂商'], factoryIndex: 0, viewNames: ['全部', '常用', '需处理'], products: [], loading: true, loadingMore: false,
    loadError: '', page: 0, totalPages: 0, totalElements: 0,
    summary: { totalProducts: 0, attentionCount: 0 },
    emptyTitle: '', emptyDesc: ''
  },

  // 默认显示全部商品；上方一排“源头厂商”可以只看某一家的货。进货账本统一放在工厂账本的“进货的”里。
  onLoad(options = {}) { this.setData({ factory: options.factory || '' }) },
  onShow() { this.loadFactories(); this.loadProducts(false) },
  loadFactories() {
    return api.getCatalogSuppliers('').then((factories) => {
      const list = (factories || []).filter((item) => item.factory && item.productCount > 0)
      this.setData({ factories: list, factoryIndex: list.findIndex((item) => item.factory === this.data.factory) + 1, factoryNames: ['全部厂商'].concat(list.map((item) => `${item.factory}（${item.productCount}）`)) })
    }).catch(() => {})
  },
  // 下拉选择（按序号）：源头厂商、商品视图
  pickFactory(event) {
    const index = Number(event.detail.value)
    const factory = index > 0 && this.data.factories[index - 1] ? this.data.factories[index - 1].factory : ''
    this.selectFactory({ currentTarget: { dataset: { factory } } })
  },
  pickView(event) {
    const view = VIEWS[Number(event.detail.value)] || 'all'
    this.selectView({ currentTarget: { dataset: { view } } })
  },
  selectFactory(event) {
    const factory = event.currentTarget.dataset.factory || ''
    if (factory === this.data.factory) return
    this.setData({ factory, factoryIndex: this.data.factories.findIndex((item) => item.factory === factory) + 1 }, () => this.loadProducts(false))
  },
  // 打开这家厂商“进货的”账本；还没有就引导去开第一张采购单。
  openFactoryLedger() {
    const factory = this.data.factory
    if (!factory) return
    api.getFactoryLedgers({ kind: 'SUPPLIER', keyword: factory }).then((result) => {
      const ledger = ((result && result.ledgers) || []).find((item) => String(item.factoryName).toLowerCase() === factory.toLowerCase())
      if (ledger) { openPage('/pages/factory-ledger-detail/index', { id: ledger.id }); return }
      wx.showModal({ title: '还没有进货账本', content: '给「' + factory + '」开一张采购单，保存后会自动建立进货账本。', confirmText: '去开单' })
        .then((choice) => { if (choice.confirm) this.openProcurement() })
    }).catch((error) => showToast((error && error.message) || '账本加载失败'))
  },
  openProcurement() { openPage('/pages/billing-editor/index', { documentType: 'PROCUREMENT', factory: this.data.factory }) },
  onPullDownRefresh() { this.loadProducts(false, true) },
  onReachBottom() { this.loadMore() },

  onKeywordInput(event) { this.setData({ keyword: event.detail.value }) },
  clearKeyword() { this.setData({ keyword: '' }, () => this.loadProducts(false)) },
  submitSearch() { this.loadProducts(false) },
  selectView(event) { this.setData({ view: event.currentTarget.dataset.view }, () => this.loadProducts(false)) },

  loadProducts(append, stopRefresh) {
    if (append && (this.data.loadingMore || this.data.page + 1 >= this.data.totalPages)) return Promise.resolve()
    const page = append ? this.data.page + 1 : 0
    this.setData(append ? { loadingMore: true } : { loading: true, loadError: '' })
    return api.getCatalog({ view: this.data.view, keyword: String(this.data.keyword || '').trim(), factory: this.data.factory, page, size: PAGE_SIZE }).then((result) => {
      const view = result || {}
      const items = (view.items || []).map(normalizeProduct)
      const products = append ? this.data.products.concat(items) : items
      this.setData({
        products, page: Number(view.page || 0), totalPages: Number(view.totalPages || 0),
        totalElements: Number(view.totalElements || 0),
        summary: { totalProducts: Number(view.totalProducts || 0), attentionCount: Number(view.attentionCount || 0) },
        ...this.emptyCopy(products.length)
      })
    }).catch((error) => {
      if (!append) this.setData({ products: [], loadError: (error && (error.message || error.errMsg)) || '商品数据加载失败' })
      else showToast((error && (error.message || error.errMsg)) || '更多商品加载失败')
    }).finally(() => {
      this.setData({ loading: false, loadingMore: false })
      if (stopRefresh) wx.stopPullDownRefresh()
    })
  },

  emptyCopy(count) {
    if (count) return { emptyTitle: '', emptyDesc: '' }
    if (this.data.keyword) return { emptyTitle: '没有找到商品', emptyDesc: '换个名称、条码或厂商关键词试试。' }
    if (this.data.factory) return { emptyTitle: '这家厂商暂时没有商品', emptyDesc: '开一张采购单，商品会自动归到这家。' }
    if (this.data.view === 'frequent') return { emptyTitle: '还没有常用商品', emptyDesc: '已确认单据中的商品会自动记录，可切换“全部”查看。' }
    if (this.data.view === 'attention') return { emptyTitle: '暂时没有需要处理的商品', emptyDesc: '低库存、未定价或库存未记录的商品会出现在这里。' }
    return { emptyTitle: '还没有商品', emptyDesc: '在单据编辑页确认单据后，商品会自动记录在这里。' }
  },

  loadMore() { return this.loadProducts(true) },
  retryLoad() { this.loadProducts(false) },
  openDetail(event) { openPage('/pages/product-detail/index', { id: event.currentTarget.dataset.id }) },
  openCreate() { openPage('/pages/product-form/index', { factory: this.data.factory }) },
  openAgent() { openPage('/pages/agent/index') },
  openEdit(event) { openPage('/pages/product-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' }) },


  openAdjust(event) {
    if (this.adjustingQuantity) return
    const product = this.data.products.find((item) => String(item.id) === String(event.currentTarget.dataset.id))
    if (!product) return
    this.adjustingQuantity = true
    modifyQuantity(product).then((saved) => { if (saved) { showToast('数量已更新', 'success'); this.loadProducts(false) } })
      .catch((error) => showToast(error.message || '数量修改失败'))
      .finally(() => { this.adjustingQuantity = false })
  },

  manageProduct(event) {
    const data = event.currentTarget.dataset
    wx.showActionSheet({ itemList: ['查看详情', '编辑资料', '查看库存流水', '删除商品'] }).then((result) => {
      if (result.tapIndex === 0) this.openDetail({ currentTarget: { dataset: data } })
      if (result.tapIndex === 1) this.openEdit({ currentTarget: { dataset: data } })
      if (result.tapIndex === 2 && data.inventoryId) openPage('/pages/inventory-detail/index', { id: data.inventoryId })
      if (result.tapIndex === 2 && !data.inventoryId) showToast('这个商品还没有库存记录')
      if (result.tapIndex === 3) this.deleteProduct({ currentTarget: { dataset: data } })
    }).catch(() => {})
  },

  deleteProduct(event) {
    const id = event.currentTarget.dataset.id
    const name = event.currentTarget.dataset.name
    wx.showModal({ title: '删除商品', content: `确认删除“${name}”吗？删除后会同步移除对应库存记录。`, confirmColor: '#B75A1A' }).then((result) => {
      if (!result.confirm) return null
      return api.deleteProduct(id).then(() => { showToast('商品已删除', 'success'); this.loadProducts(false) })
    }).catch((error) => { if (error) showToast((error.message || error.errMsg) || '商品删除失败') })
  }
})
