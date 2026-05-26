const api = require('../../utils/api')
const { openPage, showToast } = require('../../utils/router')

Page({
  data: {
    id: '',
    order: null,
    suppliers: [],
    supplierName: '',
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
    api.stockInPurchaseOrder(this.data.id).then(() => {
      showToast('采购单已入库', 'success')
      this.loadDetail()
    })
  }
})
