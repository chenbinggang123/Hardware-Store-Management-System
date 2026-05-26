const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    mode: 'create',
    typeOptions: ['C', 'B', '老客户'],
    typeIndex: 0,
    form: {
      name: '',
      type: 'C',
      phone: '',
      address: '',
      debt: 0,
      remark: ''
    }
  },

  onLoad(options) {
    const mode = options.mode || 'create'
    const id = options.id || ''
    this.setData({ id, mode })
    if (id) {
      api.getCustomer(id).then((customer) => {
        const typeIndex = this.data.typeOptions.findIndex((item) => item === customer.type)
        this.setData({
          form: customer,
          typeIndex: typeIndex < 0 ? 0 : typeIndex
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

  changeType(event) {
    const typeIndex = Number(event.detail.value)
    this.setData({
      typeIndex,
      'form.type': this.data.typeOptions[typeIndex]
    })
  },

  submit() {
    const payload = {
      ...this.data.form,
      debt: Number(this.data.form.debt || 0)
    }
    const action = this.data.mode === 'edit' ? api.updateCustomer(this.data.id, payload) : api.saveCustomer(payload)
    action.then(() => {
      showToast('客户已保存', 'success')
      setTimeout(() => wx.navigateBack({ delta: 1 }), 400)
    })
  }
})
