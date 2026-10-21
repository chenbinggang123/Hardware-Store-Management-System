const ledgerStore = require('../../utils/factory-ledger')
const api = require('../../utils/api')

Page({
  data: { id: '', form: { factoryName: '', contactPerson: '', phone: '', startDate: ledgerStore.today(), openingBalance: '0', settlementDay: '31', remark: '' }, saving: false },

  // 带 id 进来就是修改资料：只改名称、联系人、电话、结账日和备注，期初余额和流水不在这里改。
  onLoad(options) {
    const id = options.id || ''
    if (!id) return
    this.setData({ id })
    wx.setNavigationBarTitle({ title: '修改账本资料' })
    api.getFactoryLedger(id).then((ledger) => {
      if (!ledger) return
      this.setData({
        form: {
          ...this.data.form,
          factoryName: ledger.factoryName || '',
          contactPerson: ledger.contactPerson || '',
          phone: ledger.phone || '',
          settlementDay: String(ledger.settlementDay || 31),
          remark: ledger.remark || ''
        }
      })
    }).catch(() => wx.showToast({ title: '账本资料加载失败', icon: 'none' }))
  },

  updateField(event) { this.setData({ [`form.${event.currentTarget.dataset.field}`]: event.detail.value }) },
  selectDate(event) { this.setData({ 'form.startDate': event.detail.value }) },

  submit() {
    if (this.data.saving) return
    const form = this.data.form
    if (!form.factoryName.trim()) { wx.showToast({ title: '请填写工厂名称', icon: 'none' }); return }
    if (this.data.id) return this.saveEdit()
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
  },

  saveEdit() {
    const form = this.data.form
    this.setData({ saving: true })
    api.updateFactoryLedger(this.data.id, {
      factoryName: form.factoryName.trim(),
      contactPerson: form.contactPerson,
      phone: form.phone,
      settlementDay: Number(form.settlementDay) || null,
      remark: form.remark
    }).then(() => {
      wx.showToast({ title: '资料已保存', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 350)
    }).catch((error) => {
      wx.showToast({ title: (error && error.message) || '保存失败', icon: 'none' })
      this.setData({ saving: false })
    })
  }
})
