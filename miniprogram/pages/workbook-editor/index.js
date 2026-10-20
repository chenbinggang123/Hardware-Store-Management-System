const api = require('../../utils/api')
const { fileView, openAgentFile } = require('../../utils/agent-files')
const { showToast } = require('../../utils/router')

const PAGE_SIZE = 24
const MAX_ROWS = 200
const key = () => `${Date.now()}-${Math.random().toString(16).slice(2)}`
const cleanTitle = name => String(name || '表格').replace(/\.xlsx$/i, '').replace(/-修改版$/, '')
const cloneRows = rows => rows.map(row => ({ key: row.key, values: [...row.values] }))
const pageState = (sheet, requestedPage = 0) => {
  const rows = (sheet && sheet.rows) || []
  const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE))
  const pageIndex = Math.max(0, Math.min(Number(requestedPage) || 0, pageCount - 1))
  const start = pageIndex * PAGE_SIZE
  return {
    pageIndex, pageCount, pageStart: start + 1, pageEnd: Math.min(rows.length, start + PAGE_SIZE),
    visibleRows: rows.slice(start, start + PAGE_SIZE).map((row, offset) => ({ ...row, rowIndex: start + offset, rowNumber: start + offset + 1 })),
    gridWidth: 92 + (((sheet && sheet.columns) || []).length * 230)
  }
}
const toolState = sheet => ({
  activeTool: '',
  columnOptions: (sheet && sheet.columns) || [],
  fillColumnIndex: 0,
  fillScopeIndex: 0,
  fillValue: '',
  replaceColumnOptions: ['全部列'].concat((sheet && sheet.columns) || []),
  replaceColumnIndex: 0,
  searchText: '',
  replaceText: '',
  pasteColumnIndex: 0,
  pasteStartRow: '1',
  pasteText: '',
  actionMessage: '',
  canUndo: false,
  canRedo: false
})

Page({
  data: {
    outputId: '', fileName: '', title: '', sheets: [], sheetIndex: 0, activeSheet: null,
    loading: true, loadError: '', dirty: false, saving: false,
    activeTool: '', columnOptions: [], fillColumnIndex: 0, fillScopeIndex: 0, fillValue: '',
    fillScopeOptions: ['只填空白格', '覆盖整列'], replaceColumnOptions: ['全部列'], replaceColumnIndex: 0,
    searchText: '', replaceText: '', pasteColumnIndex: 0, pasteStartRow: '1', pasteText: '',
    visibleRows: [], pageIndex: 0, pageCount: 1, pageStart: 0, pageEnd: 0, gridWidth: 92,
    actionMessage: '', canUndo: false, canRedo: false
  },
  onLoad(options) {
    this.undoStack = []
    this.redoStack = []
    this.setData({ outputId: options.id || '' })
    this.load()
  },
  load() {
    this.setData({ loading: true, loadError: '' })
    return api.getAgentWorkbook(this.data.outputId).then((result) => {
      const sheets = (result.sheets || []).map(sheet => {
        const columns = sheet.columns || []
        return { ...sheet, columns, rows: (sheet.rows || []).map(values => ({ key: key(), values: columns.map((_, index) => String(values[index] == null ? '' : values[index])) })) }
      })
      const activeSheet = sheets[0] || null
      this.undoStack = []
      this.redoStack = []
      this.cellEdit = null
      this.setData({
        fileName: result.fileName || '表格.xlsx', title: `${cleanTitle(result.fileName)}-修改版`,
        sheets, sheetIndex: 0, activeSheet, dirty: false,
        ...toolState(activeSheet), ...pageState(activeSheet)
      })
    }).catch(error => this.setData({ loadError: (error && (error.message || error.errMsg)) || '表格内容读取失败' }))
      .finally(() => this.setData({ loading: false }))
  },
  selectSheet(event) {
    this.finishCellEdit()
    const sheetIndex = Number(event.currentTarget.dataset.index)
    const activeSheet = this.data.sheets[sheetIndex]
    this.undoStack = []
    this.redoStack = []
    this.setData({ sheetIndex, activeSheet, ...toolState(activeSheet), ...pageState(activeSheet) })
  },
  inputTitle(event) { this.setData({ title: event.detail.value, dirty: true }) },
  beginCellEdit(event) {
    this.finishCellEdit()
    const rowIndex = Number(event.currentTarget.dataset.row)
    const columnIndex = Number(event.currentTarget.dataset.column)
    this.cellEdit = {
      rowIndex, columnIndex,
      before: String(this.data.activeSheet.rows[rowIndex].values[columnIndex] || ''),
      snapshot: cloneRows(this.data.activeSheet.rows)
    }
  },
  inputCell(event) {
    const rowIndex = Number(event.currentTarget.dataset.row)
    const localIndex = Number(event.currentTarget.dataset.local)
    const columnIndex = Number(event.currentTarget.dataset.column)
    const value = event.detail.value
    const sheetPath = `sheets[${this.data.sheetIndex}].rows[${rowIndex}].values[${columnIndex}]`
    const activePath = `activeSheet.rows[${rowIndex}].values[${columnIndex}]`
    const visiblePath = `visibleRows[${localIndex}].values[${columnIndex}]`
    this.setData({ [sheetPath]: value, [activePath]: value, [visiblePath]: value, dirty: true, actionMessage: '' })
  },
  endCellEdit() { this.finishCellEdit() },
  finishCellEdit() {
    if (!this.cellEdit || !this.data.activeSheet) return
    const edit = this.cellEdit
    this.cellEdit = null
    const current = String(this.data.activeSheet.rows[edit.rowIndex].values[edit.columnIndex] || '')
    if (current !== edit.before) this.pushUndo(edit.snapshot)
  },
  pushUndo(snapshot) {
    this.undoStack.push(cloneRows(snapshot))
    if (this.undoStack.length > 20) this.undoStack.shift()
    this.redoStack = []
    this.setData({ canUndo: true, canRedo: false })
  },
  prepareMutation() {
    this.finishCellEdit()
    this.pushUndo(this.data.activeSheet.rows)
  },
  undo() {
    this.finishCellEdit()
    if (!this.undoStack.length) return
    this.redoStack.push(cloneRows(this.data.activeSheet.rows))
    const rows = this.undoStack.pop()
    this.replaceRows(rows, { canUndo: this.undoStack.length > 0, canRedo: true, actionMessage: '已撤销上一次修改' })
  },
  redo() {
    this.finishCellEdit()
    if (!this.redoStack.length) return
    this.undoStack.push(cloneRows(this.data.activeSheet.rows))
    const rows = this.redoStack.pop()
    this.replaceRows(rows, { canUndo: true, canRedo: this.redoStack.length > 0, actionMessage: '已恢复上一次修改' })
  },
  previousPage() { this.changePage(this.data.pageIndex - 1) },
  nextPage() { this.changePage(this.data.pageIndex + 1) },
  changePage(pageIndex) {
    this.finishCellEdit()
    this.setData(pageState(this.data.activeSheet, pageIndex))
  },
  toggleTool(event) {
    this.finishCellEdit()
    const tool = event.currentTarget.dataset.tool
    this.setData({ activeTool: this.data.activeTool === tool ? '' : tool })
  },
  changeFillColumn(event) { this.setData({ fillColumnIndex: Number(event.detail.value) }) },
  changeFillScope(event) { this.setData({ fillScopeIndex: Number(event.detail.value) }) },
  inputFillValue(event) { this.setData({ fillValue: event.detail.value }) },
  changeReplaceColumn(event) { this.setData({ replaceColumnIndex: Number(event.detail.value) }) },
  inputSearchText(event) { this.setData({ searchText: event.detail.value }) },
  inputReplaceText(event) { this.setData({ replaceText: event.detail.value }) },
  changePasteColumn(event) { this.setData({ pasteColumnIndex: Number(event.detail.value) }) },
  inputPasteStartRow(event) { this.setData({ pasteStartRow: event.detail.value }) },
  inputPasteText(event) { this.setData({ pasteText: event.detail.value }) },
  readClipboard() {
    wx.getClipboardData().then(result => {
      const text = String(result.data || '')
      if (!text) return showToast('剪贴板里没有可粘贴的内容')
      this.setData({ pasteText: text })
      showToast('已读取剪贴板', 'success')
    }).catch(() => showToast('剪贴板读取失败，可以长按输入框粘贴'))
  },
  applyFill() {
    const value = String(this.data.fillValue || '')
    if (!value.trim()) return showToast('请先填写要批量设置的内容')
    const columnIndex = Number(this.data.fillColumnIndex)
    const emptyOnly = this.data.fillScopeIndex === 0
    const rows = cloneRows(this.data.activeSheet.rows)
    let changed = 0
    rows.forEach(row => {
      const current = String(row.values[columnIndex] || '')
      if ((!emptyOnly || !current.trim()) && current !== value) {
        row.values[columnIndex] = value
        changed += 1
      }
    })
    if (!changed) return showToast(emptyOnly ? '这一列没有需要填写的空白格' : '这一列已经是相同内容')
    const apply = () => this.applyBulkRows(rows, `已批量填写 ${changed} 格`)
    if (emptyOnly) return apply()
    wx.showModal({
      title: `覆盖 ${changed} 格内容？`,
      content: `“${this.data.columnOptions[columnIndex]}”整列将改为“${value}”，操作后可撤销。`,
      confirmText: '确认覆盖', confirmColor: '#245E9B'
    }).then(result => { if (result.confirm) apply() }).catch(() => {})
  },
  applyReplace() {
    const search = String(this.data.searchText || '')
    if (!search) return showToast('请先填写要查找的内容')
    const replacement = String(this.data.replaceText || '')
    const selected = Number(this.data.replaceColumnIndex)
    const columns = selected === 0 ? this.data.activeSheet.columns.map((_, index) => index) : [selected - 1]
    const rows = cloneRows(this.data.activeSheet.rows)
    let changed = 0
    rows.forEach(row => columns.forEach(columnIndex => {
      const current = String(row.values[columnIndex] || '')
      if (!current.includes(search)) return
      row.values[columnIndex] = current.split(search).join(replacement)
      changed += 1
    }))
    if (!changed) return showToast('当前范围没有找到匹配内容')
    wx.showModal({
      title: `找到 ${changed} 格`,
      content: replacement ? `将“${search}”替换为“${replacement}”，操作后可撤销。` : `将删除匹配到的“${search}”，操作后可撤销。`,
      confirmText: '确认替换', confirmColor: '#245E9B'
    }).then(result => {
      if (result.confirm) this.applyBulkRows(rows, `已替换 ${changed} 格`)
    }).catch(() => {})
  },
  applyPaste() {
    const matrix = this.parsePaste(this.data.pasteText)
    if (!matrix.length) return showToast('请先读取剪贴板或粘贴表格内容')
    const startRow = Math.max(0, Number(this.data.pasteStartRow || 1) - 1)
    const startColumn = Number(this.data.pasteColumnIndex)
    if (startRow + matrix.length > MAX_ROWS) return showToast(`粘贴后不能超过 ${MAX_ROWS} 行`)
    const availableColumns = this.data.activeSheet.columns.length - startColumn
    const pasteColumns = Math.min(availableColumns, Math.max(...matrix.map(row => row.length)))
    if (pasteColumns <= 0) return showToast('没有可粘贴的列')
    const rows = cloneRows(this.data.activeSheet.rows)
    while (rows.length < startRow + matrix.length) rows.push({ key: key(), values: this.data.activeSheet.columns.map(() => '') })
    let changed = 0
    matrix.forEach((sourceRow, rowOffset) => {
      sourceRow.slice(0, availableColumns).forEach((value, columnOffset) => {
        const target = rows[startRow + rowOffset]
        const columnIndex = startColumn + columnOffset
        if (target.values[columnIndex] !== value) {
          target.values[columnIndex] = value
          changed += 1
        }
      })
    })
    if (!changed) return showToast('粘贴内容与当前表格相同')
    wx.showModal({
      title: `粘贴 ${matrix.length} 行 × ${pasteColumns} 列？`,
      content: `将从第 ${startRow + 1} 行“${this.data.columnOptions[startColumn]}”列开始覆盖，操作后可撤销。`,
      confirmText: '确认粘贴', confirmColor: '#245E9B'
    }).then(result => { if (result.confirm) this.applyBulkRows(rows, `已粘贴 ${matrix.length} 行数据`) }).catch(() => {})
  },
  parsePaste(value) {
    const lines = String(value || '').replace(/\r/g, '').split('\n')
    while (lines.length && !lines[lines.length - 1]) lines.pop()
    return lines.map(line => line.split('\t'))
  },
  applyBulkRows(rows, message) {
    this.prepareMutation()
    this.replaceRows(rows, { actionMessage: message, activeTool: '' })
  },
  addRow() {
    if (!this.data.activeSheet) return
    if (this.data.activeSheet.rows.length >= MAX_ROWS) return showToast(`快速编辑最多支持 ${MAX_ROWS} 行`)
    this.prepareMutation()
    const row = { key: key(), values: this.data.activeSheet.columns.map(() => '') }
    const rows = this.data.activeSheet.rows.concat(row)
    this.replaceRows(rows, { actionMessage: '已在末尾新增一行' }, Math.ceil(rows.length / PAGE_SIZE) - 1)
  },
  openRowMenu(event) {
    const rowIndex = Number(event.currentTarget.dataset.row)
    wx.showActionSheet({ itemList: ['在下方插入空白行', '复制这一行', '删除这一行'] }).then(result => {
      if (result.tapIndex === 0) this.insertRow(rowIndex)
      if (result.tapIndex === 1) this.duplicateRow(rowIndex)
      if (result.tapIndex === 2) this.deleteRow(rowIndex)
    }).catch(() => {})
  },
  insertRow(rowIndex) {
    if (this.data.activeSheet.rows.length >= MAX_ROWS) return showToast(`快速编辑最多支持 ${MAX_ROWS} 行`)
    this.prepareMutation()
    const rows = cloneRows(this.data.activeSheet.rows)
    rows.splice(rowIndex + 1, 0, { key: key(), values: this.data.activeSheet.columns.map(() => '') })
    this.replaceRows(rows, { actionMessage: `已在第 ${rowIndex + 1} 行下方插入空白行` })
  },
  duplicateRow(rowIndex) {
    if (this.data.activeSheet.rows.length >= MAX_ROWS) return showToast(`快速编辑最多支持 ${MAX_ROWS} 行`)
    const source = this.data.activeSheet.rows[rowIndex]
    if (!source) return
    this.prepareMutation()
    const rows = cloneRows(this.data.activeSheet.rows)
    rows.splice(rowIndex + 1, 0, { key: key(), values: [...source.values] })
    this.replaceRows(rows, { actionMessage: `已复制第 ${rowIndex + 1} 行` })
  },
  deleteRow(rowIndex) {
    wx.showModal({ title: `删除第 ${rowIndex + 1} 行？`, content: '保存新版本后，这一行不会出现在新文件中；原文件不受影响。', confirmText: '删除', confirmColor: '#B94C3A' })
      .then(result => {
        if (!result.confirm) return
        this.prepareMutation()
        const rows = this.data.activeSheet.rows.filter((_, index) => index !== rowIndex)
        this.replaceRows(rows, { actionMessage: `已删除第 ${rowIndex + 1} 行` })
      }).catch(() => {})
  },
  replaceRows(rows, extra = {}, requestedPage = this.data.pageIndex) {
    const sheets = this.data.sheets.slice()
    const activeSheet = { ...sheets[this.data.sheetIndex], rows }
    sheets[this.data.sheetIndex] = activeSheet
    this.setData({ sheets, activeSheet, dirty: true, ...pageState(activeSheet, requestedPage), ...extra })
  },
  save() {
    if (this.data.saving) return
    this.finishCellEdit()
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
