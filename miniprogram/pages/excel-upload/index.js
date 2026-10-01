const workflow = require('../../utils/excel-workflow')
const { openPage, showToast } = require('../../utils/router')

const relationTypes = ['源头厂商', 'ToB 买家', 'ToC 买家', '内部经营', '暂不关联']
const relationNames = {
  '源头厂商': ['宁波五金厂', '江苏机电设备厂', '佛山建材厂'],
  'ToB 买家': ['城建安装公司', '老王五金工程队', '宏达物业'],
  'ToC 买家': ['散客', '社区团购客户'],
  '内部经营': ['一号仓', '门店库房'],
  '暂不关联': ['暂不关联']
}
const purposes = ['买家询价', '厂家价格', '商品资料', '库存盘点', '采购到货', '其他文件']

Page({
  data: {
    relationTypes, purposes, relationNameOptions: relationNames[relationTypes[0]],
    relationTypeIndex: 0, relationNameIndex: 0, purposeIndex: 0,
    file: null, recognizing: false
  },

  chooseFile() {
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['xls', 'xlsx', 'csv'] }).then((result) => {
      const file = result.tempFiles && result.tempFiles[0]
      if (!file) return
      this.setData({ file: { name: file.name, sizeText: this.formatSize(file.size), path: file.path || file.tempFilePath } })
    }).catch(() => {})
  },

  useDemoFile() {
    this.setData({ file: { name: '新到询价单.xlsx', sizeText: '36 KB', path: '' } })
  },

  formatSize(size) {
    const bytes = Number(size || 0)
    return bytes > 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
  },

  changeRelationType(event) {
    const relationTypeIndex = Number(event.detail.value)
    this.setData({ relationTypeIndex, relationNameIndex: 0, relationNameOptions: relationNames[relationTypes[relationTypeIndex]] })
  },

  changeRelationName(event) { this.setData({ relationNameIndex: Number(event.detail.value) }) },
  changePurpose(event) { this.setData({ purposeIndex: Number(event.detail.value) }) },

  startRecognition() {
    if (!this.data.file) return showToast('请先选择 Excel 或 CSV 文件')
    if (this.data.recognizing) return
    this.setData({ recognizing: true })
    const relationType = relationTypes[this.data.relationTypeIndex]
    const relationName = this.data.relationNameOptions[this.data.relationNameIndex]
    const task = workflow.createTask({ fileName: this.data.file.name, relationType, relationName, purpose: purposes[this.data.purposeIndex] })
    setTimeout(() => {
      this.setData({ recognizing: false })
      openPage('/pages/excel-mapping/index', { id: task.id }, { replace: true })
    }, 650)
  }
})
