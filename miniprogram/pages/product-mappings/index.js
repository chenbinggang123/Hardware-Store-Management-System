const memory = require('../../utils/supply-chain-memory')
const { showToast } = require('../../utils/router')

Page({
  data: {
    productId: 2,
    productName: '冲击电钻',
    filter: 'ALL',
    filters: [
      { key: 'ALL', label: '全部关系' },
      { key: 'FACTORY', label: '厂家货号' },
      { key: 'BUYER', label: '买家叫法' }
    ],
    mappings: [],
    visibleMappings: [],
    activeCount: 0
  },

  onLoad(options) {
    this.setData({ productId: Number(options.id || 2), productName: options.name || '冲击电钻' })
  },
  onShow() { this.loadMappings() },

  loadMappings() {
    const mappings = memory.getMappings(this.data.productId)
    const first = mappings[0]
    this.setData({
      mappings,
      productName: first ? first.productName : this.data.productName,
      activeCount: mappings.filter((item) => item.status === 'ACTIVE').length
    }, () => this.refresh())
  },

  refresh() {
    const { mappings, filter } = this.data
    let visibleMappings = mappings
    if (filter === 'FACTORY') visibleMappings = mappings.filter((item) => item.sourceType === '厂家货号')
    if (filter === 'BUYER') visibleMappings = mappings.filter((item) => item.sourceType === '买家叫法')
    this.setData({ visibleMappings })
  },

  changeFilter(event) { this.setData({ filter: event.currentTarget.dataset.key }, () => this.refresh()) },
  addMapping() { showToast('可在下次 Excel 处理时确认并保存新映射', 'none') },
  revoke(event) {
    const id = Number(event.currentTarget.dataset.id)
    const item = this.data.mappings.find((mapping) => mapping.id === id)
    if (!item || item.status === 'REVOKED') return
    wx.showModal({
      title: '停用这条映射？',
      content: `以后遇到“${item.sourceName}”时将不再自动匹配，但历史记录会保留。`,
      confirmText: '停用映射',
      confirmColor: '#B94C3A',
      success: (result) => {
        if (!result.confirm) return
        memory.revokeMapping(id)
        showToast('映射已停用', 'success')
        this.loadMappings()
      }
    })
  }
})
