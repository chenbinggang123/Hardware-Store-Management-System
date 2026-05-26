const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    mode: 'create',
    suppliers: [],
    products: [],
    supplierIndex: 0,
    form: {
      supplierId: '',
      operatorId: 1,
      remark: '',
      items: [
        {
          productId: '',
          quantity: 1,
          price: '',
          locationId: ''
        }
      ]
    }
  },

  onLoad(options) {
    const mode = options.mode || 'create'
    const id = options.id || ''
    this.setData({ id, mode })
    Promise.all([api.getSuppliers(), api.getProducts(), id ? api.getPurchaseOrder(id) : Promise.resolve(null)]).then(([suppliers, products, order]) => {
      const nextData = { suppliers, products }
      if (order) {
        nextData.form = {
          supplierId: order.supplierId,
          operatorId: order.operatorId,
          remark: order.remark || '',
          items: (order.items || []).map((item) => ({
            productId: item.productId,
            quantity: item.quantity,
            price: item.price,
            locationId: item.locationId || ''
          }))
        }
        nextData.supplierIndex = Math.max(0, suppliers.findIndex((item) => Number(item.id) === Number(order.supplierId)))
      } else if (suppliers.length) {
        nextData.form = {
          ...this.data.form,
          supplierId: suppliers[0].id
        }
      }
      this.setData(nextData)
    })
  },

  changeSupplier(event) {
    const supplierIndex = Number(event.detail.value)
    this.setData({
      supplierIndex,
      'form.supplierId': this.data.suppliers[supplierIndex].id
    })
  },

  updateRemark(event) {
    this.setData({
      'form.remark': event.detail.value
    })
  },

  addItem() {
    const items = this.data.form.items.concat([{ productId: '', quantity: 1, price: '', locationId: '' }])
    this.setData({ 'form.items': items })
  },

  removeItem(event) {
    const index = Number(event.currentTarget.dataset.index)
    const items = this.data.form.items.filter((_, itemIndex) => itemIndex !== index)
    this.setData({ 'form.items': items.length ? items : [{ productId: '', quantity: 1, price: '', locationId: '' }] })
  },

  changeItemProduct(event) {
    const index = Number(event.currentTarget.dataset.index)
    const product = this.data.products[Number(event.detail.value)]
    this.setData({
      [`form.items[${index}].productId`]: product.id,
      [`form.items[${index}].price`]: product.costPrice,
      [`form.items[${index}].locationId`]: product.locationId || ''
    })
  },

  updateItemField(event) {
    const index = Number(event.currentTarget.dataset.index)
    const key = event.currentTarget.dataset.key
    this.setData({
      [`form.items[${index}].${key}`]: event.detail.value
    })
  },

  submit() {
    const payload = {
      supplierId: Number(this.data.form.supplierId),
      operatorId: 1,
      remark: this.data.form.remark,
      items: this.data.form.items
        .filter((item) => item.productId)
        .map((item) => {
          const product = this.data.products.find((productItem) => Number(productItem.id) === Number(item.productId))
          return {
            productId: Number(item.productId),
            productName: product ? product.name : '',
            quantity: Number(item.quantity || 0),
            price: Number(item.price || 0),
            locationId: item.locationId || ''
          }
        })
    }
    const action = this.data.mode === 'edit' ? api.updatePurchaseOrder(this.data.id, payload) : api.savePurchaseOrder(payload)
    action.then(() => {
      showToast('采购单已保存', 'success')
      setTimeout(() => wx.navigateBack({ delta: 1 }), 400)
    })
  }
})
