const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    inventory: null,
    actualQuantity: '',
    reason: ''
  },

  onLoad(options) {
    this.setData({ id: options.id || '' })
    api.getInventory(this.data.id).then((inventory) => {
      this.setData({
        inventory,
        actualQuantity: inventory ? inventory.quantity : ''
      })
    })
  },

  updateField(event) {
    const key = event.currentTarget.dataset.key
    this.setData({
      [key]: event.detail.value
    })
  },

  submit() {
    api.adjustInventory(this.data.id, {
      actualQuantity: Number(this.data.actualQuantity || 0),
      reason: this.data.reason,
      operatorId: 1
    }).then(() => {
      showToast('库存已调整', 'success')
      setTimeout(() => wx.navigateBack({ delta: 1 }), 400)
    })
  }
})
