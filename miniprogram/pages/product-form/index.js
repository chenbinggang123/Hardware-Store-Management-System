const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

function createDefaultForm() {
  return {
    name: '',
    barcode: '',
    spec: '',
    unit: '件',
    unitConvert: '',
    retailPrice: '',
    wholesalePrice: '',
    oldCustomerPrice: '',
    costPrice: '',
    stock: 0,
    locationId: '',
    sourceFactory: '',
    imageUrl: '',
    status: 1
  }
}

Page({
  data: {
    id: '',
    mode: 'create',
    saving: false,
    uploadingImage: false,
    originalImageUrl: '',
    pendingImageUrl: '',
    form: createDefaultForm()
  },

  onLoad(options) {
    const mode = options.mode || 'create'
    const id = options.id || ''
    this.setData({ id, mode })
    if (id) {
      api.getProduct(id).then((product) => {
        this.setData({
          form: {
            ...createDefaultForm(),
            ...product
          },
          originalImageUrl: product.imageUrl || ''
        })
      })
    }
  },

  onUnload() {
    if (this.data.pendingImageUrl) wx.cloud.deleteFile({ fileList: [this.data.pendingImageUrl] }).catch(() => null)
  },

  updateField(event) {
    const key = event.currentTarget.dataset.key
    this.setData({
      [`form.${key}`]: event.detail.value
    })
  },

  chooseImage() {
    if (this.data.uploadingImage) return
    wx.chooseMedia({ count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['compressed'] }).then((result) => {
      const file = result.tempFiles && result.tempFiles[0]
      if (!file || !file.tempFilePath) return null
      if (Number(file.size || 0) > 3 * 1024 * 1024) throw new Error('图片压缩后仍超过 3MB，请重新选择')
      const extension = String(file.tempFilePath).split('.').pop().toLowerCase() || 'jpg'
      const operatorId = (getApp().globalData.currentOperatorId || 'unknown')
      const cloudPath = `product-images/${operatorId}/${Date.now()}-${Math.random().toString(36).slice(2, 10)}.${extension}`
      this.setData({ uploadingImage: true })
      return wx.cloud.uploadFile({ cloudPath, filePath: file.tempFilePath }).then((uploaded) => {
        const previousPending = this.data.pendingImageUrl
        this.setData({ 'form.imageUrl': uploaded.fileID, pendingImageUrl: uploaded.fileID })
        if (previousPending && previousPending !== uploaded.fileID) wx.cloud.deleteFile({ fileList: [previousPending] }).catch(() => null)
      })
    }).catch((error) => showToast((error && (error.message || error.errMsg)) || '图片上传失败')).finally(() => this.setData({ uploadingImage: false }))
  },

  removeImage() {
    const pending = this.data.pendingImageUrl
    this.setData({ 'form.imageUrl': '', pendingImageUrl: '' })
    if (pending) wx.cloud.deleteFile({ fileList: [pending] }).catch(() => null)
  },

  validateForm(payload) {
    if (!payload.name.trim()) {
      showToast('请填写商品名称')
      return false
    }
    if (!payload.barcode.trim()) {
      showToast('请填写商品条码')
      return false
    }
    if (!payload.spec.trim()) {
      showToast('请填写商品规格')
      return false
    }
    if (!payload.unit.trim()) {
      showToast('请填写商品单位')
      return false
    }
    if (payload.retailPrice < 0 || payload.wholesalePrice < 0 || payload.oldCustomerPrice < 0 || payload.costPrice < 0) {
      showToast('价格不能小于 0')
      return false
    }
    if (payload.stock < 0) {
      showToast('库存不能小于 0')
      return false
    }
    return true
  },

  submit() {
    if (this.data.saving) {
      return
    }

    const payload = {
      ...this.data.form,
      name: `${this.data.form.name || ''}`.trim(),
      barcode: `${this.data.form.barcode || ''}`.trim(),
      spec: `${this.data.form.spec || ''}`.trim(),
      unit: `${this.data.form.unit || ''}`.trim(),
      unitConvert: `${this.data.form.unitConvert || ''}`.trim(),
      retailPrice: Number(this.data.form.retailPrice || 0),
      wholesalePrice: Number(this.data.form.wholesalePrice || 0),
      oldCustomerPrice: Number(this.data.form.oldCustomerPrice || 0),
      costPrice: Number(this.data.form.costPrice || 0),
      stock: Number(this.data.form.stock || 0),
      locationId: `${this.data.form.locationId || ''}`.trim(),
      sourceFactory: `${this.data.form.sourceFactory || ''}`.trim(),
      imageUrl: `${this.data.form.imageUrl || ''}`.trim()
    }

    if (!this.validateForm(payload)) {
      return
    }

    api.getProducts().then((products) => {
      const duplicate = products.find((item) => {
        return `${item.barcode || ''}`.trim() === payload.barcode
          && `${item.id}` !== `${this.data.id || ''}`
      })

      if (duplicate) {
        showToast('商品条码已存在')
        return
      }

      this.setData({ saving: true })
      const action = this.data.mode === 'edit' ? api.updateProduct(this.data.id, payload) : api.saveProduct(payload)
      action.then((savedProduct) => {
        const original = this.data.originalImageUrl
        if (original && original !== payload.imageUrl && String(original).startsWith('cloud://')) wx.cloud.deleteFile({ fileList: [original] }).catch(() => null)
        this.setData({ pendingImageUrl: '', originalImageUrl: payload.imageUrl })
        showToast('商品已保存', 'success')
        setTimeout(() => {
          wx.redirectTo({
            url: `/pages/product-detail/index?id=${savedProduct.id}`
          })
        }, 400)
      }).finally(() => {
        this.setData({ saving: false })
      })
    })
  }
})
