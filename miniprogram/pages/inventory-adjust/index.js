const api = require('../../utils/api')
const { showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    inventory: null,
    actualQuantity: '',
    reason: '',
    saving: false
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
    if (this.data.saving) return
    const actualQuantity = Number(this.data.actualQuantity)
    if (!Number.isFinite(actualQuantity) || actualQuantity < 0) return showToast('请填写正确的实盘数量')
    if (!(this.data.reason || '').trim()) return showToast('请填写调整原因')
    wx.showModal({
      title: '确认更新库存',
      content: `库存将从 ${this.data.inventory.quantity} 件改为 ${actualQuantity} 件，并生成库存日志。价格和商品资料不会改变。`,
      confirmText: '确认更新'
    }).then((result) => {
      if (!result.confirm) return null
      this.setData({ saving: true })
      return api.adjustInventory(this.data.id, { actualQuantity, reason: this.data.reason, operatorId: 1 }).then(() => {
        showToast('库存已调整', 'success')
        setTimeout(() => wx.navigateBack({ delta: 1 }), 400)
      }).catch((error) => showToast((error && (error.message || error.errMsg)) || '库存调整失败'))
        .finally(() => this.setData({ saving: false }))
    }).catch(() => {})
  }
})
