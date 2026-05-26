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
    statusOptions: ['上架', '下架'],
    statusIndex: 0,
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
          statusIndex: Number(product.status) === 1 ? 0 : 1
        })
      })
    }
  },

  updateField(event) {
    const key = event.currentTarget.dataset.key
    this.setData({
      [`form.${key}`]: event.detail.value
    })
  },

  changeStatus(event) {
    const statusIndex = Number(event.detail.value)
    this.setData({
      statusIndex,
      'form.status': statusIndex === 0 ? 1 : 0
    })
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
