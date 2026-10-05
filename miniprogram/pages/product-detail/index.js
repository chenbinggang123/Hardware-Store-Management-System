const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

function normalizeProduct(product) {
  if (!product) {
    return null
  }
  const stock = Number(product.stock || 0)
  const status = Number(product.status || 0)
  const name = product.name || '商品'
  return {
    id: product.id,
    name,
    initial: `${name}`.slice(0, 1),
    specText: product.spec || '--',
    unitText: product.unit || '--',
    sourceFactoryText: product.sourceFactory || '未设置厂商',
    imageUrl: product.imageUrl || '',
    retailPrice: product.retailPrice || 0,
    wholesalePrice: product.wholesalePrice || 0,
    oldCustomerPrice: product.oldCustomerPrice || 0,
    costPrice: product.costPrice || 0,
    stock,
    locationText: product.locationId || '--',
    barcodeText: product.barcode || '--',
    unitConvertText: product.unitConvert || '--',
    createTimeText: product.createTime || '--',
    status,
    statusText: status === 1 ? '上架' : '下架',
    toggleStatusText: status === 1 ? '下架商品' : '上架商品',
    lowStock: stock <= 10
  }
}

Page({
  data: {
    id: '',
    product: null,
    loading: true,
    loadError: ''
  },

  onLoad(options) {
    this.setData({
      id: options.id || ''
    })
    this.loadDetail()
  },

  onShow() {
    if (this.data.id) {
      this.loadDetail()
    }
  },

  loadDetail() {
    if (!this.data.id) {
      this.setData({ loading: false, loadError: '缺少商品编号' })
      return
    }
    this.setData({ loading: true, loadError: '' })
    api.getProduct(this.data.id).then((product) => {
      this.setData({
        product: normalizeProduct(product), loading: false
      })
    }).catch((error) => this.setData({ product: null, loading: false, loadError: (error && (error.message || error.errMsg)) || '商品详情加载失败' }))
  },

  openEdit() {
    openPage('/pages/product-form/index', {
      id: this.data.id,
      mode: 'edit'
    })
  },

  openMappings() {
    openPage('/pages/product-mappings/index', { id: this.data.id, name: this.data.product.name })
  },

  openPriceHistory() {
    openPage('/pages/price-history/index', { id: this.data.id, name: this.data.product.name })
  },

  openPriceSync() {
    openPage('/pages/price-sync-review/index', { productId: this.data.id })
  },

  toggleStatus() {
    const product = this.data.product
    if (!product) {
      return
    }
    const nextStatus = product.status === 1 ? 0 : 1
    const actionText = nextStatus === 1 ? '上架' : '下架'
    wx.showModal({ title: `${actionText}商品`, content: `${actionText}后会改变开单和查询时的可用状态，不会修改库存和价格。`, confirmText: `确认${actionText}` }).then((result) => {
      if (!result.confirm) return null
      return api.changeProductStatus(product.id, nextStatus).then(() => { showToast(`商品已${actionText}`, 'success'); this.loadDetail() })
    }).catch((error) => showToast((error && (error.message || error.errMsg)) || '状态更新失败'))
  },

  deleteProduct() {
    const product = this.data.product
    if (!product) {
      return
    }
    wx.showModal({
      title: '删除商品',
      content: `确认删除“${product.name}”吗？删除后会同步移除对应库存记录，且无法恢复。`,
      confirmColor: '#B94C3A',
      success: (result) => {
        if (!result.confirm) {
          return
        }
        api.deleteProduct(product.id).then(() => {
          showToast('商品已删除', 'success')
          setTimeout(() => {
            wx.navigateBack({ delta: 1 })
          }, 400)
        }).catch((error) => showToast((error && (error.message || error.errMsg)) || '商品删除失败'))
      }
    })
  },
  retryLoad() { this.loadDetail() }
})
