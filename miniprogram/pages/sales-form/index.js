const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    mode: 'create',
    customers: [],
    products: [],
    customerIndex: 0,
    form: {
      customerId: '',
      operatorId: 1,
      receivedAmount: 0,
      items: [
        {
          productId: '',
          quantity: 1,
          price: ''
        }
      ]
    }
  },

  onLoad(options) {
    const mode = options.mode || 'create'
    const id = options.id || ''
    this.setData({ id, mode })
    Promise.all([api.getCustomers(), api.getProducts(), id ? api.getSalesOrder(id) : Promise.resolve(null)]).then(([customers, products, order]) => {
      const nextData = { customers, products }
      if (order) {
        nextData.form = {
          customerId: order.customerId,
          operatorId: order.operatorId,
          receivedAmount: order.receivedAmount,
          items: (order.items || []).map((item) => ({
            productId: item.productId,
            quantity: item.quantity,
            price: item.price
          }))
        }
        nextData.customerIndex = Math.max(0, customers.findIndex((item) => Number(item.id) === Number(order.customerId)))
      } else if (customers.length) {
        nextData.form = {
          ...this.data.form,
          customerId: customers[0].id
        }
      }
      this.setData(nextData)
    })
  },

  resolveDefaultPrice(productId) {
    const product = this.data.products.find((item) => Number(item.id) === Number(productId))
    const customer = this.data.customers[this.data.customerIndex]
    if (!product) {
      return ''
    }
    if (customer && customer.type === 'B') {
      return product.wholesalePrice
    }
    if (customer && customer.type === '老客户') {
      return product.oldCustomerPrice
    }
    return product.retailPrice
  },

  changeCustomer(event) {
    const customerIndex = Number(event.detail.value)
    this.setData({
      customerIndex,
      'form.customerId': this.data.customers[customerIndex].id
    })
  },

  updateReceived(event) {
    this.setData({
      'form.receivedAmount': event.detail.value
    })
  },

  addItem() {
    const items = this.data.form.items.concat([{ productId: '', quantity: 1, price: '' }])
    this.setData({ 'form.items': items })
  },

  removeItem(event) {
    const index = Number(event.currentTarget.dataset.index)
    const items = this.data.form.items.filter((_, itemIndex) => itemIndex !== index)
    this.setData({ 'form.items': items.length ? items : [{ productId: '', quantity: 1, price: '' }] })
  },

  changeItemProduct(event) {
    const index = Number(event.currentTarget.dataset.index)
    const product = this.data.products[Number(event.detail.value)]
    this.setData({
      [`form.items[${index}].productId`]: product.id,
      [`form.items[${index}].price`]: this.resolveDefaultPrice(product.id)
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
      customerId: Number(this.data.form.customerId),
      operatorId: 1,
      receivedAmount: Number(this.data.form.receivedAmount || 0),
      items: this.data.form.items
        .filter((item) => item.productId)
        .map((item) => {
          const product = this.data.products.find((productItem) => Number(productItem.id) === Number(item.productId))
          return {
            productId: Number(item.productId),
            productName: product ? product.name : '',
            quantity: Number(item.quantity || 0),
            price: Number(item.price || 0)
          }
        })
    }
    const action = this.data.mode === 'edit' ? api.updateSalesOrder(this.data.id, payload) : api.saveSalesOrder(payload)
    action.then(() => {
      showToast('销售单已保存', 'success')
      setTimeout(() => wx.navigateBack({ delta: 1 }), 400)
    })
  }
})
