const api = require('./api')

function modifyQuantity(product) {
  return wx.showModal({ title: '修改商品数量', editable: true, content: String(product.quantity === undefined ? product.stock || 0 : product.quantity), placeholderText: '填写当前实际数量', confirmText: '保存数量' }).then((choice) => {
    if (!choice.confirm) return null
    const text = String(choice.content || '').trim()
    const quantity = Number(text)
    if (!/^\d+$/.test(text) || !Number.isSafeInteger(quantity) || quantity > 2147483647) throw new Error('请填写大于或等于 0 的整数数量')
    return api.ensureInventory(product.id).then((inventory) => api.adjustInventory(inventory.id, { actualQuantity: quantity, reason: '手动修正商品数量' }))
  })
}
module.exports = { modifyQuantity }
