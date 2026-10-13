const api = require('./api')

function sizeText(value) {
  const bytes = Number(value) || 0
  if (!bytes) return ''
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function timeText(value) {
  if (!value) return ''
  const text = String(value).replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

function detailText(summary) {
  const source = summary || {}
  const parts = []
  const sheets = Array.isArray(source.sheets) ? source.sheets.length : 0
  if (sheets) parts.push(`${sheets} 个工作表`)
  if (source.rowCount !== undefined && source.rowCount !== null) parts.push(`${source.rowCount} 行`)
  if (Number(source.mismatchCount) > 0) parts.push(`${source.mismatchCount} 处待核对`)
  return parts.join(' · ')
}

function fileView(item) {
  const source = item || {}
  const status = source.status || 'READY'
  const mimeType = String(source.mimeType || '')
  const fileType = /pdf/.test(mimeType) ? 'pdf' : 'xlsx'
  const statusText = { READY: '可打开', FAILED: '生成失败', MISSING: '文件已丢失，请删除后重新生成' }[status] || '生成中'
  return {
    ...source,
    name: source.name || `文件.${fileType}`,
    fileType,
    mark: fileType === 'pdf' ? 'PDF' : '表',
    ready: status === 'READY',
    failed: status === 'FAILED' || status === 'MISSING',
    statusText,
    sizeText: sizeText(source.size),
    timeText: timeText(source.createTime),
    detailText: detailText(source.summary)
  }
}

function safeFileName(file) {
  return String(file.name || `文件-${file.id}.${file.fileType || 'xlsx'}`).replace(/[\\/:*?"<>|]/g, '_')
}

function openAgentFile(file) {
  if (!file || !file.id) return Promise.reject({ message: '文件信息不完整' })
  if (file.ready === false) {
    return Promise.reject({ message: file.status === 'MISSING' ? '文件内容已丢失，请删除后让小五重新生成' : file.failed ? '文件生成失败，无法打开' : '文件还在生成中' })
  }
  const filePath = `${wx.env.USER_DATA_PATH}/${Date.now()}-${safeFileName(file)}`
  return api.downloadAgentOutput(file.id).then((data) => new Promise((resolve, reject) => {
    wx.getFileSystemManager().writeFile({ filePath, data, success: resolve, fail: reject })
  })).then(() => new Promise((resolve, reject) => {
    wx.openDocument({ filePath, fileType: file.fileType || 'xlsx', showMenu: true, success: resolve, fail: reject })
  }))
}

function confirmDeleteAgentFile(file) {
  if (!file || !file.id) return Promise.resolve(false)
  return wx.showModal({
    title: '删除这个文件？',
    content: `“${file.name}”删除后无法恢复。商品、库存、订单等业务数据不会受影响。`,
    confirmText: '删除文件',
    confirmColor: '#B94C3A'
  }).then((result) => {
    if (!result.confirm) return false
    return api.deleteAgentOutput(file.id).then(() => true)
  })
}

const PENDING_PROMPT_KEY = 'agent_pending_prompt'

/** Sends the user back to the agent tab with a prefilled request to change this file. */
function askAgentToModify(file, openPage) {
  if (!file || !file.id) return
  wx.setStorageSync(PENDING_PROMPT_KEY, `请修改文件 #${file.id}「${file.name}」：`)
  openPage('/pages/agent/index')
}

module.exports = { fileView, openAgentFile, confirmDeleteAgentFile, askAgentToModify, PENDING_PROMPT_KEY }
