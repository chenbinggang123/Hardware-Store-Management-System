const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    order: null,
    suppliers: [],
    supplierName: '',
    saving: false,
    loading: true,
    loadError: ''
  },

  onLoad(options) {
    this.setData({ id: options.id || '' })
    this.loadDetail()
  },

  onShow() {
    if (this.data.id) {
      this.loadDetail()
    }
  },

  loadDetail() {
    this.setData({
      loading: true,
      loadError: ''
    })
    Promise.all([api.getPurchaseOrder(this.data.id), api.getSuppliers()]).then(([order, suppliers]) => {
      const supplier = suppliers.find((item) => Number(item.id) === Number(order ? order.supplierId : ''))
      this.setData({
        order,
        suppliers,
        supplierName: supplier ? supplier.name : `供应商 ${(order && order.supplierId) || '--'}`
      })
    }).catch((error) => {
      this.setData({
        order: null,
        suppliers: [],
        supplierName: '',
        loadError: (error && (error.message || error.errMsg)) || '采购单详情加载失败'
      })
    }).finally(() => {
      this.setData({ loading: false })
    })
  },

  openEdit() {
    openPage('/pages/purchase-form/index', { id: this.data.id, mode: 'edit' })
  },

  stockIn() {
    if (this.data.saving) return
    wx.showModal({ title: '确认采购入库', content: '确认后将增加这张采购单中所有商品的库存，并记录入库日志。不会修改零售价。', confirmText: '确认入库' }).then((result) => {
      if (!result.confirm) return null
      this.setData({ saving: true })
      return api.stockInPurchaseOrder(this.data.id).then(() => { showToast('采购单已入库', 'success'); this.loadDetail() })
        .catch((error) => showToast((error && (error.message || error.errMsg)) || '入库失败'))
        .finally(() => this.setData({ saving: false }))
    }).catch(() => {})
  }
})
