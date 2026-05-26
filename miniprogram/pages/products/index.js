const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

function normalizeProduct(item) {
  const stock = Number(item.stock || 0)
  const status = Number(item.status || 0)
  return {
    id: item.id,
    name: item.name || '',
    barcode: item.barcode || '--',
    spec: item.spec || '--',
    unit: item.unit || '--',
    retailPrice: item.retailPrice || 0,
    wholesalePrice: item.wholesalePrice || 0,
    oldCustomerPrice: item.oldCustomerPrice || 0,
    stock,
    locationText: item.locationId || '--',
    sourceFactoryText: item.sourceFactory || '--',
    status,
    statusText: status === 1 ? '上架' : '下架',
    nextStatus: status === 1 ? 0 : 1,
    nextStatusText: status === 1 ? '下架' : '上架',
    lowStock: stock <= 10
  }
}

function buildMetrics(products) {
  const total = products.length
  const online = products.filter((item) => item.status === 1).length
  const lowStock = products.filter((item) => item.lowStock).length
  return { total, online, lowStock }
}

Page({
  data: {
    keyword: '',
    status: '',
    loading: true,
    hasProducts: false,
    products: [],
    metrics: { total: 0, online: 0, lowStock: 0 },
    currentCount: 0
  },

  onLoad() {
    this.loadProducts()
  },

  onShow() {
    this.loadProducts()
  },

  onPullDownRefresh() {
    this.loadProducts(true)
  },

  onKeywordInput(event) {
    this.setData({ keyword: event.detail.value })
  },

  clearKeyword() {
    this.setData({ keyword: '' }, () => this.loadProducts())
  },

  selectStatus(event) {
    this.setData({ status: event.currentTarget.dataset.status }, () => this.loadProducts())
  },

  loadProducts(stopPullDownRefresh) {
    const params = {}
    if (this.data.keyword) params.keyword = this.data.keyword
    if (this.data.status !== '') params.status = this.data.status

    this.setData({ loading: true })

    Promise.all([api.getProducts(params), api.getProducts()])
      .then((result) => {
        const currentProducts = (result[0] || []).map(normalizeProduct)
        const allProducts = (result[1] || []).map(normalizeProduct)
        this.setData({
          loading: false,
          products: currentProducts,
          hasProducts: currentProducts.length > 0,
          currentCount: currentProducts.length,
          metrics: buildMetrics(allProducts)
        })
      })
      .catch(() => {
        this.setData({
          loading: false,
          products: [],
          hasProducts: false,
          currentCount: 0,
          metrics: { total: 0, online: 0, lowStock: 0 }
        })
      })
      .finally(() => {
        if (stopPullDownRefresh) wx.stopPullDownRefresh()
      })
  },

  openDetail(event) {
    openPage('/pages/product-detail/index', { id: event.currentTarget.dataset.id })
  },

  openCreate() {
    openPage('/pages/product-form/index')
  },

  openEdit(event) {
    openPage('/pages/product-form/index', { id: event.currentTarget.dataset.id, mode: 'edit' })
  },

  toggleStatus(event) {
    const id = event.currentTarget.dataset.id
    const status = event.currentTarget.dataset.status
    api.changeProductStatus(id, status).then(() => {
      showToast('商品状态已更新', 'success')
      this.loadProducts()
    })
  },

  deleteProduct(event) {
    const id = event.currentTarget.dataset.id
    const name = event.currentTarget.dataset.name
    wx.showModal({
      title: '删除商品',
      content: '确认删除"' + name + '"吗？删除后会同步移除对应库存记录。',
      confirmColor: '#B75A1A',
      success: (result) => {
        if (!result.confirm) return
        api.deleteProduct(id).then(() => {
          showToast('商品已删除', 'success')
          this.loadProducts()
        })
      }
    })
  }
})
