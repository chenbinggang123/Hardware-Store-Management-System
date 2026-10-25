const api = require('../../utils/api')
const { modifyQuantity } = require('../../utils/product-quantity')
const { openPage, showToast } = require('../../utils/router')
const sourcePhotos = require('../../utils/source-photo')

const TYPE_NAMES = { PURCHASE: '拿货单', RETURN: '退货单', PROCUREMENT: '采购单' }

// 单据原图：有行位置就截出这个商品那一行，没有就显示整张缩略图。
function photoView(item, url, cropWidth) {
  const amount = item.quantity !== null && item.quantity !== undefined
    ? `${item.quantity}${item.unit || ''}${item.price !== null && item.price !== undefined ? ' × ¥' + item.price : ''}` : ''
  return {
    key: `${item.documentId}`,
    documentId: item.documentId,
    url,
    crop: sourcePhotos.rowCrop(item.box, item.width, item.height, cropWidth),
    title: [item.date, TYPE_NAMES[item.documentType] || '单据', item.customerName].filter(Boolean).join(' · '),
    detail: [amount, item.documentNumber].filter(Boolean).join(' · ')
  }
}

function normalizeProduct(product, inventory) {
  if (!product) {
    return null
  }
  const inventoryRecorded = Boolean(inventory)
  const stock = Number(inventoryRecorded ? inventory.quantity : product.stock || 0)
  const warningThreshold = Number(inventoryRecorded ? inventory.warningThreshold : 10)
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
    inventoryId: inventory && inventory.id,
    inventoryRecorded,
    warningThreshold,
    locationText: (inventory && inventory.locationId) || product.locationId || '--',
    barcodeText: product.barcode || '--',
    unitConvertText: product.unitConvert || '--',
    createTimeText: product.createTime || '--',
    stockStateText: !inventoryRecorded ? '未记录' : stock <= 0 ? '缺货' : stock <= warningThreshold ? '不多' : '有货',
    lowStock: inventoryRecorded && stock <= warningThreshold
  }
}

Page({
  data: {
    id: '',
    product: null,
    loading: true,
    loadError: '',
    photos: []
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
    Promise.all([api.getProduct(this.data.id), api.getInventories({})]).then(([product, inventories]) => {
      const inventory = (inventories || []).find((item) => `${item.productId}` === `${this.data.id}`)
      this.setData({
        product: normalizeProduct(product, inventory), loading: false
      })
      this.loadPhotos()
    }).catch((error) => this.setData({ product: null, loading: false, loadError: (error && (error.message || error.errMsg)) || '商品详情加载失败' }))
  },

  // 这个商品出现过的单据原图（拍照开单时保存的纸单照片）
  loadPhotos() {
    return api.getProductPhotos(this.data.id).then((items) => {
      const list = (items || []).filter((item) => item.cloudFileId)
      if (!list.length) { this.setData({ photos: [] }); return }
      return sourcePhotos.tempUrls(list.map((item) => item.cloudFileId)).then((urls) => {
        const cropWidth = sourcePhotos.pxFromRpx(640)
        this.setData({ photos: list.filter((item) => urls[item.cloudFileId]).map((item) => photoView(item, urls[item.cloudFileId], cropWidth)) })
      })
    }).catch(() => this.setData({ photos: [] }))
  },

  previewPhoto(event) {
    const photo = this.data.photos[Number(event.currentTarget.dataset.index)]
    if (photo) sourcePhotos.preview(photo.url, this.data.photos.map((item) => item.url))
  },

  openPhotoDocument(event) {
    const id = event.currentTarget.dataset.id
    if (id) openPage('/pages/billing-editor/index', { id })
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

  openInventoryAdjust() {
    if (!this.data.product || this.adjustingQuantity) return
    this.adjustingQuantity = true
    modifyQuantity(this.data.product).then((saved) => { if (saved) this.loadDetail() })
      .catch((error) => showToast(error.message || '数量修改失败'))
      .finally(() => { this.adjustingQuantity = false })
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
