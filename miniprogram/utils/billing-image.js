const WIDTH = 1120
const MARGIN = 30
const TABLE_WIDTH = WIDTH - MARGIN * 2
const COLUMNS = [58, 245, 145, 78, 98, 125, 145, 166]
const HEADERS = ['序号', '商品名称', '规格', '单位', '数量', '单价', '金额', '备注']

function text(value, fallback = '') {
  return value === null || value === undefined || value === '' ? fallback : String(value)
}

function money(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(2) : text(value, '—')
}

function font(context, size, weight = 'normal') {
  context.font = `${weight} ${size}px sans-serif`
}

function wrap(context, value, maxWidth, maxLines = 4) {
  const source = text(value, '—')
  const lines = []
  let current = ''
  for (const character of source) {
    const next = current + character
    if (current && context.measureText(next).width > maxWidth) {
      lines.push(current)
      current = character
      if (lines.length === maxLines - 1) break
    } else current = next
  }
  if (current && lines.length < maxLines) lines.push(current)
  if (lines.length === maxLines && source.length > lines.join('').length) {
    let last = lines[maxLines - 1]
    while (last && context.measureText(`${last}…`).width > maxWidth) last = last.slice(0, -1)
    lines[maxLines - 1] = `${last}…`
  }
  return lines.length ? lines : ['—']
}

function rowHeight(context, row) {
  font(context, 21)
  const values = [row.name, row.specification, row.unit, row.quantity, row.price, row.amountText, row.remark]
  const widths = COLUMNS.slice(1)
  let lineCount = 1
  values.forEach((value, index) => { lineCount = Math.max(lineCount, wrap(context, value, widths[index] - 16, 4).length) })
  return Math.min(142, Math.max(62, 18 + lineCount * 31))
}

function line(context, x1, y1, x2, y2, width = 1) {
  context.lineWidth = width
  context.beginPath()
  context.moveTo(x1, y1)
  context.lineTo(x2, y2)
  context.stroke()
}

function centered(context, value, x, y, width, height, size = 21, weight = 'normal', maxLines = 4) {
  font(context, size, weight)
  const rows = wrap(context, value, width - 16, maxLines)
  const step = size * 1.42
  let baseline = y + (height - rows.length * step) / 2 + step * 0.76
  context.textAlign = 'center'
  context.textBaseline = 'alphabetic'
  rows.forEach((row) => { context.fillText(row, x + width / 2, baseline); baseline += step })
}

function left(context, value, x, y, maxWidth, size = 19, weight = 'normal', maxLines = 3) {
  font(context, size, weight)
  context.textAlign = 'left'
  context.textBaseline = 'top'
  const rows = wrap(context, value, maxWidth, maxLines)
  rows.forEach((row, index) => context.fillText(row, x, y + index * size * 1.5))
  return rows.length * size * 1.5
}

function canvasNode(page) {
  return new Promise((resolve, reject) => {
    wx.createSelectorQuery().in(page).select('#billingImageCanvas').fields({ node: true, size: true }).exec((result) => {
      const canvas = result && result[0] && result[0].node
      if (canvas) resolve(canvas)
      else reject(new Error('图片画布尚未准备好，请稍后重试'))
    })
  })
}

function toTempFile(canvas, width, height) {
  return new Promise((resolve, reject) => {
    wx.canvasToTempFilePath({ canvas, x: 0, y: 0, width, height, destWidth: width, destHeight: height, fileType: 'png', quality: 1,
      success: (result) => resolve(result.tempFilePath), fail: reject })
  })
}

function errorMessage(error) {
  return String(error && (error.errMsg || error.message) || error || '')
}

function downloadFile(url) {
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url,
      success: (result) => {
        if (Number(result.statusCode || 200) >= 400 || !result.tempFilePath) {
          reject(new Error('生成的图片没有成功下载到本地'))
          return
        }
        resolve(result.tempFilePath)
      },
      fail: reject
    })
  })
}

function unlinkQuietly(fileSystem, filePath) {
  return new Promise((resolve) => {
    fileSystem.unlink({ filePath, success: resolve, fail: resolve })
  })
}

/**
 * canvasToTempFilePath occasionally returns a devtools/temporary URL. The album
 * API may then try to fetch it again and fail with a TLS socket error. Copying
 * it into USER_DATA_PATH guarantees that saveImageToPhotosAlbum receives a
 * stable local path on both real devices and the developer tools.
 */
function stableLocalFile(filePath) {
  const sourceTask = /^https?:\/\//i.test(String(filePath || '')) ? downloadFile(filePath) : Promise.resolve(filePath)
  return sourceTask.then((sourcePath) => {
    if (!sourcePath) throw new Error('图片临时文件没有生成成功')
    if (!wx.getFileSystemManager || !wx.env || !wx.env.USER_DATA_PATH) return sourcePath
    const fileSystem = wx.getFileSystemManager()
    const targetPath = `${wx.env.USER_DATA_PATH}/billing-receipt-latest.png`
    if (sourcePath === targetPath) return targetPath
    return unlinkQuietly(fileSystem, targetPath).then(() => new Promise((resolve, reject) => {
      fileSystem.copyFile({ srcPath: sourcePath, destPath: targetPath, success: () => resolve(targetPath), fail: reject })
    }))
  })
}

function previewImage(filePath) {
  return new Promise((resolve, reject) => {
    if (!wx.previewImage) {
      reject(new Error('当前环境无法打开图片预览'))
      return
    }
    wx.previewImage({ current: filePath, urls: [filePath], success: resolve, fail: reject })
  })
}

function saveToAlbum(filePath) {
  return new Promise((resolve, reject) => wx.saveImageToPhotosAlbum({ filePath, success: resolve, fail: reject }))
    .catch((error) => {
      if (!/auth deny|authorize|permission/i.test(String(error && (error.errMsg || error.message) || ''))) throw error
      return wx.showModal({ title: '需要相册权限', content: '请在设置中允许保存到相册，然后返回继续保存。', confirmText: '打开设置' })
        .then((choice) => {
          if (!choice.confirm) throw new Error('没有获得相册权限')
          return wx.openSetting()
        }).then((settings) => {
          if (!settings.authSetting || !settings.authSetting['scope.writePhotosAlbum']) throw new Error('相册权限仍未开启')
          return saveToAlbum(filePath)
        })
    })
}

function shouldPreviewInstead(error) {
  return /network socket|secure tls|connection.*established|devtools|not support|system error|privacy agreement|api scope is not declared/i.test(errorMessage(error))
}

function render(page, content, options = {}) {
  const rows = Array.isArray(content.lines) ? content.lines : []
  if (!rows.length) return Promise.reject(new Error('没有可生成图片的商品明细'))
  if (rows.length > 50) return Promise.reject(new Error('图片最多支持 50 项商品，请使用 Excel 导出完整单据'))
  return canvasNode(page).then((canvas) => {
    const sizing = canvas.getContext('2d')
    if (!sizing) throw new Error('当前设备无法创建图片画布')
    font(sizing, 21)
    const heights = rows.map((row) => rowHeight(sizing, row))
    const remarkHeight = content.remark ? 62 : 0
    const height = 286 + heights.reduce((sum, value) => sum + value, 0) + remarkHeight + 150
    canvas.width = WIDTH
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('当前设备无法创建图片画布')
    context.fillStyle = '#FFFFFF'
    context.fillRect(0, 0, WIDTH, height)
    context.fillStyle = '#111111'
    context.strokeStyle = '#303632'
    context.lineJoin = 'miter'

    left(context, text(content.companyName, '本店名称未填写'), MARGIN, 34, 420, 31, 'bold', 2)
    font(context, 36, 'bold'); context.textAlign = 'center'; context.textBaseline = 'top'; context.fillText(content.documentType === 'PROCUREMENT' ? '采 购 单' : (content.documentType === 'RETURN' ? '退 货 单' : '拿 货 单'), WIDTH / 2, 30)
    font(context, 17); context.textAlign = 'right'; context.fillText(`单号：${text(options.number, '草稿')}`, WIDTH - MARGIN, 42)
    line(context, MARGIN, 91, WIDTH - MARGIN, 91, 2)
    left(context, `联系电话：${text(content.phone, '未填写')}`, MARGIN, 107, 440)
    left(context, `单据日期：${text(content.date, '未填写')}`, WIDTH - 345, 107, 315)
    line(context, MARGIN, 141, WIDTH - MARGIN, 141)
    left(context, `${content.documentType === 'PROCUREMENT' ? '源头厂商' : '购货单位'}：${text(content.customerName, '未填写')}`, MARGIN, 155, 500, 20)
    left(context, `备注：${text(content.remark, '无')}`, 615, 155, WIDTH - MARGIN - 615, 19, 'normal', 2)

    let y = 212
    let x = MARGIN
    const headerHeight = 58
    context.fillStyle = '#F3F5F3'; context.fillRect(MARGIN, y, TABLE_WIDTH, headerHeight); context.fillStyle = '#111111'
    COLUMNS.forEach((width, index) => { centered(context, HEADERS[index], x, y, width, headerHeight, 19, 'bold', 2); line(context, x, y, x, y + headerHeight); x += width })
    line(context, WIDTH - MARGIN, y, WIDTH - MARGIN, y + headerHeight); line(context, MARGIN, y, WIDTH - MARGIN, y); line(context, MARGIN, y + headerHeight, WIDTH - MARGIN, y + headerHeight)
    y += headerHeight
    rows.forEach((row, index) => {
      const height = heights[index]
      const values = [index + 1, row.name, row.specification, row.unit, row.quantity, money(row.price), money(row.amountText), row.remark]
      x = MARGIN
      COLUMNS.forEach((width, column) => { centered(context, values[column], x, y, width, height, 20, 'normal', column === 1 || column === 7 ? 4 : 3); line(context, x, y, x, y + height); x += width })
      line(context, WIDTH - MARGIN, y, WIDTH - MARGIN, y + height); line(context, MARGIN, y + height, WIDTH - MARGIN, y + height)
      y += height
    })
    const labelWidth = COLUMNS.slice(0, 6).reduce((sum, value) => sum + value, 0)
    centered(context, '合计', MARGIN, y, labelWidth, 58, 22, 'bold')
    centered(context, `¥${money(options.total)}`, MARGIN + labelWidth, y, COLUMNS[6], 58, 22, 'bold')
    centered(context, '人民币', MARGIN + labelWidth + COLUMNS[6], y, COLUMNS[7], 58, 18)
    ;[MARGIN, MARGIN + labelWidth, MARGIN + labelWidth + COLUMNS[6], WIDTH - MARGIN].forEach((position) => line(context, position, y, position, y + 58))
    line(context, MARGIN, y, WIDTH - MARGIN, y); line(context, MARGIN, y + 58, WIDTH - MARGIN, y + 58, 2)
    y += 75
    if (content.remark) y += left(context, `整单备注：${content.remark}`, MARGIN, y, TABLE_WIDTH, 18, 'normal', 3) + 12
    left(context, content.documentType === 'PROCUREMENT' ? '金额按确认明细计算；已确认采购单会同步商品资料与进货数量。' : '金额按最终确认的数量与单价计算。', MARGIN, y, TABLE_WIDTH, 17)
    y += 44
    left(context, '经手人：________________', MARGIN, y, 420, 19)
    font(context, 19); context.textAlign = 'right'; context.textBaseline = 'top'; context.fillText('客户签收：________________', WIDTH - MARGIN, y)
    return toTempFile(canvas, WIDTH, height)
  })
}

function renderAndSave(page, content, options) {
  return render(page, content, options)
    .then(stableLocalFile)
    .then((filePath) => saveToAlbum(filePath)
      .then(() => ({ filePath, mode: 'album' }))
      .catch((error) => {
        if (!shouldPreviewInstead(error)) throw error
        return previewImage(filePath).then(() => ({ filePath, mode: 'preview', originalError: errorMessage(error) }))
      }))
}

module.exports = { render, renderAndSave, stableLocalFile }
