const api = require('../../utils/api')
const { fileView, openAgentFile } = require('../../utils/agent-files')
const { showToast } = require('../../utils/router')

const key = () => `${Date.now()}-${Math.random().toString(16).slice(2)}`
const cleanTitle = name => String(name || '表格').replace(/\.xlsx$/i, '').replace(/-修改版$/, '')

Page({
  data: {
    outputId: '', fileName: '', title: '', sheets: [], sheetIndex: 0, activeSheet: null,
    loading: true, loadError: '', dirty: false, saving: false
  },
  onLoad(options) { this.setData({ outputId: options.id || '' }); this.load() },
  load() {
    this.setData({ loading: true, loadError: '' })
    return api.getAgentWorkbook(this.data.outputId).then((result) => {
      const sheets = (result.sheets || []).map(sheet => {
        const columns = sheet.columns || []
        return { ...sheet, columns, rows: (sheet.rows || []).map(values => ({ key: key(), values: columns.map((_, index) => String(values[index] == null ? '' : values[index])) })) }
      })
      this.setData({
        fileName: result.fileName || '表格.xlsx', title: `${cleanTitle(result.fileName)}-修改版`,
        sheets, sheetIndex: 0, activeSheet: sheets[0] || null, dirty: false
      })
    }).catch(error => this.setData({ loadError: (error && (error.message || error.errMsg)) || '表格内容读取失败' }))
      .finally(() => this.setData({ loading: false }))
  },
  selectSheet(event) {
    const sheetIndex = Number(event.currentTarget.dataset.index)
    this.setData({ sheetIndex, activeSheet: this.data.sheets[sheetIndex] })
  },
  inputTitle(event) { this.setData({ title: event.detail.value, dirty: true }) },
  inputCell(event) {
    const rowIndex = Number(event.currentTarget.dataset.row)
    const columnIndex = Number(event.currentTarget.dataset.column)
    const value = event.detail.value
    const sheetPath = `sheets[${this.data.sheetIndex}].rows[${rowIndex}].values[${columnIndex}]`
    const activePath = `activeSheet.rows[${rowIndex}].values[${columnIndex}]`
    this.setData({ [sheetPath]: value, [activePath]: value, dirty: true })
  },
  addRow() {
    if (!this.data.activeSheet) return
    const row = { key: key(), values: this.data.activeSheet.columns.map(() => '') }
    const rows = this.data.activeSheet.rows.concat(row)
    this.replaceRows(rows)
  },
  duplicateRow(event) {
    const rowIndex = Number(event.currentTarget.dataset.row)
    const source = this.data.activeSheet.rows[rowIndex]
    if (!source) return
    const rows = this.data.activeSheet.rows.slice()
    rows.splice(rowIndex + 1, 0, { key: key(), values: [...source.values] })
    this.replaceRows(rows)
  },
  deleteRow(event) {
    const rowIndex = Number(event.currentTarget.dataset.row)
    wx.showModal({ title: '删除这一行？', content: '保存新版本后，这一行不会出现在新文件中；原文件不受影响。', confirmText: '删除', confirmColor: '#B94C3A' })
      .then(result => {
        if (!result.confirm) return
        const rows = this.data.activeSheet.rows.filter((_, index) => index !== rowIndex)
        this.replaceRows(rows)
      }).catch(() => {})
  },
  replaceRows(rows) {
    const sheets = this.data.sheets.slice()
    const activeSheet = { ...sheets[this.data.sheetIndex], rows }
    sheets[this.data.sheetIndex] = activeSheet
    this.setData({ sheets, activeSheet, dirty: true })
  },
  save() {
    if (this.data.saving) return
    const sheets = this.data.sheets.map(sheet => ({
      name: sheet.name,
      columns: sheet.columns.map(name => ({ name })),
      rows: sheet.rows.map(row => row.values),
      totalColumns: sheet.columns.filter(name => /金额|总额|合计|未结|已收|已付/.test(name))
    })).filter(sheet => sheet.rows.length)
    if (!sheets.length) return showToast('至少保留一行数据')
    this.setData({ saving: true })
    api.saveAgentWorkbookVersion(this.data.outputId, { title: String(this.data.title || '').trim(), sheets })
      .then(result => {
        this.setData({ dirty: false })
        return wx.showModal({ title: '新版本已生成', content: '原文件仍然保留。现在打开新版本吗？', confirmText: '打开文件', cancelText: '稍后' })
          .then(choice => {
            if (!choice.confirm) return null
            return openAgentFile(fileView({ id: result.outputId, name: result.fileName, size: result.fileSize, status: result.status, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }))
          })
      }).catch(error => showToast((error && (error.message || error.errMsg)) || '新版本生成失败'))
      .finally(() => this.setData({ saving: false }))
  },
  retry() { this.load() }
})
