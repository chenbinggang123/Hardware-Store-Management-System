const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    mode: 'create',
    form: {
      name: '',
      contact: '',
      phone: '',
      address: '',
      remark: ''
    }
  },

  onLoad(options) {
    const mode = options.mode || 'create'
    const id = options.id || ''
    this.setData({ id, mode })
    if (id) {
      api.getSupplier(id).then((supplier) => {
        this.setData({ form: supplier })
      })
    }
  },

  updateField(event) {
    const key = event.currentTarget.dataset.key
    this.setData({
      [`form.${key}`]: event.detail.value
    })
  },

  submit() {
    const action = this.data.mode === 'edit' ? api.updateSupplier(this.data.id, this.data.form) : api.saveSupplier(this.data.form)
    action.then(() => {
      showToast('供应商已保存', 'success')
      setTimeout(() => wx.navigateBack({ delta: 1 }), 400)
    })
  }
})
