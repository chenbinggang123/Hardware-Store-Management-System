const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')

Page({
  data: { form: { factoryName: '', contactPerson: '', phone: '', startDate: ledgerStore.today(), openingBalance: '0', settlementDay: '31', remark: '' }, saving: false },
  updateField(event) { this.setData({ [`form.${event.currentTarget.dataset.field}`]: event.detail.value }) },
  selectDate(event) { this.setData({ 'form.startDate': event.detail.value }) },
  submit() {
    if (this.data.saving) return
    const form = this.data.form
    if (!form.factoryName.trim()) { wx.showToast({ title: '请填写工厂名称', icon: 'none' }); return }
    wx.showModal({
      title: '确认建立这本账？',
      content: `${form.factoryName}\n联系人：${form.contactPerson || '未填写'}\n期初未结：¥${Number(form.openingBalance || 0).toFixed(2)}`,
      confirmText: '确认新建',
      success: (result) => {
        if (!result.confirm) return
        this.setData({ saving: true })
        api.createFactoryLedger(form).then((ledger) => {
          wx.showToast({ title: '账本已建立', icon: 'success' })
          setTimeout(() => wx.redirectTo({ url: `/pages/factory-ledger-detail/index?id=${encodeURIComponent(ledger.id)}` }), 350)
        }).catch((error) => {
          wx.showToast({ title: error.message || '新建失败', icon: 'none' })
          this.setData({ saving: false })
        })
      }
    })
  }
})
