const cloudConfig = require('../config/cloud')

const AUTH_STORAGE_KEY = 'auth_session'
const TEMP_AGENT_CLEANUP_KEY = 'agent_temp_cleanup_by_run'

function getAuthSession() {
  const session = wx.getStorageSync(AUTH_STORAGE_KEY)
  if (!session || !session.token) {
    return null
  }
  return session
}

function saveAuthSession(session) {
  wx.setStorageSync(AUTH_STORAGE_KEY, session)
}

function clearAuthSession() {
  wx.removeStorageSync(AUTH_STORAGE_KEY)
  const app = getApp && getApp()
  if (app && typeof app.clearSession === 'function') {
    app.clearSession(false)
  }
}

function redirectToLogin() {
  const pages = getCurrentPages()
  const current = pages[pages.length - 1]
  if (current && current.route === 'pages/login/index') {
    return
  }
  wx.reLaunch({
    url: '/pages/login/index'
  })
}

function shouldFallback(error) {
  if (!error) {
    return false
  }
  if (typeof error.statusCode === 'number') {
    return false
  }
  if (typeof error.success === 'boolean') {
    return false
  }
  return true
}

function request(path, options = {}) {
  if (!cloudConfig.envId || !cloudConfig.serviceName || !wx.cloud || !wx.cloud.callContainer) {
    return Promise.reject({ message: 'CloudBase 云托管环境尚未配置' })
  }
  return cloudRequest(path, options)
}

function cloudRequest(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase()
  const session = getAuthSession()
  const header = {
    'content-type': 'application/json',
    'X-WX-SERVICE': cloudConfig.serviceName,
    ...(options.header || {})
  }
  if (session && session.token) header.Authorization = `Bearer ${session.token}`
  return wx.cloud.callContainer({
    config: { env: cloudConfig.envId },
    path: `${cloudConfig.apiPrefix}${path}`,
    method,
    data: options.data || options.query || {},
    header,
    ...(options.responseType ? { responseType: options.responseType } : {})
  }).then((response) => {
    const payload = response.data
    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (payload && typeof payload === 'object' && Object.prototype.hasOwnProperty.call(payload, 'success')) {
        return payload.success ? payload.data : Promise.reject(payload)
      }
      return payload
    }
    if (response.statusCode === 401 && path !== '/auth/login') {
      clearAuthSession()
      redirectToLogin()
    }
    return Promise.reject(payload || { message: '云托管请求失败', statusCode: response.statusCode })
  })
}

function agentRequest(path, options = {}) {
  if (!cloudConfig.envId || !cloudConfig.serviceName || !wx.cloud || !wx.cloud.callContainer) {
    return Promise.reject({ message: '经营助手尚未配置云托管环境' })
  }
  return cloudRequest(path, options)
}

function localFileName(file) {
  if (file && file.name) return file.name
  const path = file && (file.path || file.tempFilePath) ? (file.path || file.tempFilePath) : file
  return String(path || 'attachment').replace(/\\/g, '/').split('/').pop()
}

function localFilePath(file) {
  return file && (file.path || file.tempFilePath) ? (file.path || file.tempFilePath) : file
}

function localFileSize(file, filePath) {
  if (file && Number(file.size) > 0) return Promise.resolve(Number(file.size))
  return new Promise((resolve, reject) => {
    wx.getFileInfo({ filePath, success: (result) => resolve(result.size), fail: reject })
  })
}

function cloudPathFor(fileName, category = 'agent') {
  const session = getAuthSession()
  const operatorId = session && session.user && session.user.id ? session.user.id : 'unknown'
  const now = new Date()
  const extension = fileName.includes('.') ? `.${fileName.split('.').pop().toLowerCase()}` : ''
  const datePath = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('/')
  return `${category}/${operatorId}/${datePath}/${Date.now()}-${Math.random().toString(36).slice(2, 10)}${extension}`
}

function removeCloudFile(fileID) {
  if (!fileID || !wx.cloud || !wx.cloud.deleteFile) return Promise.resolve()
  return wx.cloud.deleteFile({ fileList: [fileID] }).catch(() => null)
}

// 服务端确认没有入账的原图会把云文件 ID 返回来，由这里去云存储删除。
function dropCloudFiles(result) {
  const files = result && result.cloudFiles
  if (!files || !files.length || !wx.cloud || !wx.cloud.deleteFile) return Promise.resolve(result)
  return wx.cloud.deleteFile({ fileList: files }).catch(() => null).then(() => result)
}

function updateTemporaryCleanupQueue(updater) {
  const current = wx.getStorageSync(TEMP_AGENT_CLEANUP_KEY) || []
  wx.setStorageSync(TEMP_AGENT_CLEANUP_KEY, updater(Array.isArray(current) ? current : []))
}

function queueTemporaryAgentFiles(runId, attachments) {
  if (!runId) return
  const cloudFileIds = (attachments || []).map((item) => item && item.cloudFileId).filter(Boolean)
  if (!cloudFileIds.length) return
  updateTemporaryCleanupQueue((items) => {
    const kept = items.filter((item) => String(item.runId) !== String(runId))
    return kept.concat({ runId, cloudFileIds: Array.from(new Set(cloudFileIds)) })
  })
}

function cleanupTemporaryAgentFiles(runId) {
  const current = wx.getStorageSync(TEMP_AGENT_CLEANUP_KEY) || []
  const item = (Array.isArray(current) ? current : []).find((entry) => String(entry.runId) === String(runId))
  if (!item || !item.cloudFileIds || !item.cloudFileIds.length) return Promise.resolve(true)
  if (!wx.cloud || !wx.cloud.deleteFile) return Promise.resolve(false)
  return Promise.all(item.cloudFileIds.map((fileID) => wx.cloud.deleteFile({ fileList: [fileID] })
    .then((result) => {
      const files = result && result.fileList
      return Array.isArray(files) && files.length > 0 && files.every((file) => !file.status) ? null : fileID
    })
    .catch(() => fileID))).then((failed) => {
    const remaining = failed.filter(Boolean)
    updateTemporaryCleanupQueue((items) => remaining.length
      ? items.map((entry) => String(entry.runId) === String(runId) ? { ...entry, cloudFileIds: remaining } : entry)
      : items.filter((entry) => String(entry.runId) !== String(runId)))
    return remaining.length === 0
  })
}

function cleanupTerminalTemporaryAgentFiles() {
  const current = wx.getStorageSync(TEMP_AGENT_CLEANUP_KEY) || []
  const queued = Array.isArray(current) ? current.slice() : []
  return Promise.all(queued.map((item) => agentRequest(`/agent/runs/${item.runId}`)
    .then((run) => run && ['COMPLETED', 'CANCELLED', 'EXPIRED'].includes(run.status)
      ? cleanupTemporaryAgentFiles(item.runId) : false)
    .catch(() => false)))
}

function uploadAgentFile(file, conversationId, saveToFiles = false, category = '') {
  if (!wx.cloud || !wx.cloud.uploadFile || !wx.cloud.getTempFileURL) {
    return Promise.reject({ message: '当前微信基础库不支持 CloudBase 文件上传' })
  }
  const filePath = localFilePath(file)
  const originalName = localFileName(file)
  let uploadedFileID = ''
  return localFileSize(file, filePath)
    .then((fileSize) => wx.cloud.uploadFile({
      cloudPath: cloudPathFor(originalName, category || (saveToFiles ? 'agent-files' : 'agent-temp')), filePath
    })
      .then((uploadResult) => {
        uploadedFileID = uploadResult.fileID
        return wx.cloud.getTempFileURL({ fileList: [uploadedFileID] })
      })
      .then((urlResult) => {
        const item = urlResult.fileList && urlResult.fileList[0]
        if (!item || !item.tempFileURL || (item.status && item.status !== 0)) {
          return Promise.reject({ message: (item && item.errMsg) || '无法获取附件临时读取地址' })
        }
        return agentRequest('/agent/attachments/cloud', {
          method: 'POST',
          data: { cloudFileId: uploadedFileID, downloadUrl: item.tempFileURL, originalName, fileSize, conversationId, saveToFiles }
        })
      })
      .then((attachment) => ({ ...attachment, cloudFileId: uploadedFileID })))
    .catch((error) => removeCloudFile(uploadedFileID).then(() => Promise.reject(error)))
}

function uploadExcelFile(file, purpose, context = {}) {
  if (!wx.cloud || !wx.cloud.uploadFile || !wx.cloud.getTempFileURL) {
    return Promise.reject({ message: '当前微信基础库不支持文件上传' })
  }
  const filePath = localFilePath(file)
  const originalName = localFileName(file)
  let uploadedFileID = ''
  return localFileSize(file, filePath)
    .then((fileSize) => wx.cloud.uploadFile({ cloudPath: cloudPathFor(originalName, 'excel-tasks'), filePath })
      .then((uploadResult) => {
        uploadedFileID = uploadResult.fileID
        return wx.cloud.getTempFileURL({ fileList: [uploadedFileID] })
      })
      .then((urlResult) => {
        const item = urlResult.fileList && urlResult.fileList[0]
        if (!item || !item.tempFileURL || (item.status && item.status !== 0)) {
          return Promise.reject({ message: (item && item.errMsg) || '无法获取文件临时读取地址' })
        }
        return request('/excel-tasks/cloud', {
          method: 'POST',
          data: {
            cloudFileId: uploadedFileID,
            downloadUrl: item.tempFileURL,
            originalName,
            fileSize,
            purpose,
            supplierId: context.supplierId || null,
            priceEffectiveDate: context.priceEffectiveDate || null
          }
        })
      }))
    .catch((error) => removeCloudFile(uploadedFileID).then(() => Promise.reject(error)))
}

function sanitizeUser(user) {
  if (!user) {
    return null
  }
  return {
    id: Number(user.id),
    username: user.username,
    name: user.name,
    role: user.role,
    status: user.status
  }
}

module.exports = {
  login(data) {
    return request('/auth/login', { method: 'POST', data }).catch((error) => {
      if (shouldFallback(error)) {
        const detail = error && (error.errMsg || error.message)
        return Promise.reject({
          success: false,
          message: detail
            ? `无法连接后端服务：${detail}`
            : '无法连接后端服务，请检查 HTTPS 域名、Nginx 和 Spring Boot 是否正常运行'
        })
      }
      return Promise.reject(error)
    }).then((session) => {
      const normalized = {
        token: session.token,
        user: sanitizeUser(session)
      }
      saveAuthSession(normalized)
      return normalized
    })
  },
  getCurrentUser() {
    return request('/auth/me').then((session) => ({
      token: session.token,
      user: sanitizeUser(session)
    }))
  },
  logout() {
    return request('/auth/logout', { method: 'POST' })
      .catch((error) => {
        if (shouldFallback(error)) {
          return { success: true }
        }
        return Promise.resolve({ success: true })
      })
      .then((result) => {
        clearAuthSession()
        return result
      })
  },
  getStoredSession() {
    return getAuthSession()
  },
  sendAgentMessage(content, conversationId, attachmentIds) {
    return agentRequest('/agent/messages', { method: 'POST', data: { content, conversationId, attachmentIds: attachmentIds || [] } })
  },
  startAgentMessageStream(content, conversationId, attachmentIds) {
    return agentRequest('/agent/messages/stream', { method: 'POST', data: { content, conversationId, attachmentIds: attachmentIds || [] } })
  },
  uploadAgentAttachment(filePath, conversationId, saveToFiles = false) {
    return uploadAgentFile(filePath, conversationId, saveToFiles)
  },
  // 拍照开单：原图单独放在 billing-photo 目录，识别后不删除，开单页核对时要看。
  uploadBillingPhoto(file) {
    return uploadAgentFile(file, null, false, 'billing-photo')
  },
  startBillingRecognition(attachmentId, documentType) {
    return agentRequest('/billing/recognitions', { method: 'POST', data: { attachmentId, documentType } })
  },
  getProductPhotos(productId) {
    return request('/billing/product-photos', { data: { productId } })
  },
  getBillingSource(id, attachmentId) {
    return request(`/billing/documents/${id}/source`, { data: attachmentId ? { attachmentId } : {} })
  },
  queueTemporaryAgentFiles(runId, attachments) {
    queueTemporaryAgentFiles(runId, attachments)
  },
  cleanupTemporaryAgentFiles(runId) {
    return cleanupTemporaryAgentFiles(runId)
  },
  cleanupTerminalTemporaryAgentFiles() {
    return cleanupTerminalTemporaryAgentFiles()
  },
  deleteAgentAttachment(attachment) {
    const attachmentId = typeof attachment === 'object' ? attachment.id : attachment
    const cloudFileId = typeof attachment === 'object' ? attachment.cloudFileId : ''
    return agentRequest(`/agent/attachments/${attachmentId}`, { method: 'DELETE' })
      .then((result) => result === false ? result : removeCloudFile(cloudFileId).then(() => result))
  },
  getSavedPhotoSource(id) {
    return agentRequest(`/agent/attachments/${id}/photo-source`)
  },
  downloadSavedPhoto(id) {
    return agentRequest(`/agent/attachments/${id}/photo-content`, { responseType: 'arraybuffer' })
  },
  getAgentConversations() {
    return agentRequest('/agent/conversations')
  },
  getAgentConversation(conversationId) {
    return agentRequest(`/agent/conversations/${conversationId}`)
  },
  renameAgentConversation(conversationId, title) {
    return agentRequest(`/agent/conversations/${conversationId}/title`, { method: 'PATCH', data: { title } })
  },
  deleteAgentConversation(conversationId) {
    return agentRequest(`/agent/conversations/${conversationId}`, { method: 'DELETE' })
      .then((cloudFileIds) => Promise.all((cloudFileIds || []).map(removeCloudFile)))
  },
  getAgentRun(runId) {
    return agentRequest(`/agent/runs/${runId}`)
  },
  cancelAgentRun(runId) {
    return agentRequest(`/agent/runs/${runId}/cancel`, { method: 'POST' })
  },
  resolveAgentApproval(runId, approved) {
    return agentRequest(`/agent/runs/${runId}/approval`, { method: 'POST', data: { approved } })
  },
  getBillingDocument(id) {
    return request(`/billing/documents/${id}`)
  },
  createBillingDocument(data) {
    return request('/billing/documents', { method: 'POST', data })
  },
  saveBillingDocument(id, data) {
    return request(`/billing/documents/${id}`, { method: 'PUT', data })
  },
  confirmBillingDocument(id, version) {
    return request(`/billing/documents/${id}/confirm`, { method: 'POST', data: { version } })
  },
  getMonthlySummary(month) {
    return request('/reports/monthly-summary', { query: month ? { month } : {} })
  },
  deleteBillingDocument(id) {
    return request(`/billing/documents/${id}`, { method: 'DELETE' }).then(dropCloudFiles)
  },
  deleteLedgerBillingDocument(ledgerId, transactionId) {
    return request(`/billing/ledgers/${ledgerId}/transactions/${transactionId}/document`, { method: 'DELETE' }).then(dropCloudFiles)
  },
  // 放弃没有入账的原图：服务端只删没入账的，已入账的会保留
  discardBillingPhoto(attachmentId) {
    if (!attachmentId) return Promise.resolve(null)
    return request(`/billing/photos/${attachmentId}`, { method: 'DELETE' }).then(dropCloudFiles).catch(() => null)
  },
  getUnpostedProcurements() {
    return request('/billing/procurements/unposted')
  },
  backfillProcurements() {
    return request('/billing/procurements/backfill', { method: 'POST' })
  },
  // 打开开单记录时顺带清理 14 天还没入账的原图
  cleanupBillingPhotos() {
    return request('/billing/photos/cleanup', { method: 'POST' }).then(dropCloudFiles).catch(() => null)
  },
  deleteBillingDrafts(ids) {
    return request('/billing/documents/drafts', { method: 'DELETE', data: { ids } }).then(dropCloudFiles)
  },
  getBillingRecords(params = {}) {
    return request('/billing/records', { query: params })
  },
  getBillingLedgerPosting(id) {
    return request(`/billing/documents/${id}/ledger`)
  },
  postBillingToLedger(id, ledgerId) {
    return request(`/billing/documents/${id}/ledger`, { method: 'POST', data: { ledgerId } })
  },
  exportBillingDocument(id) {
    return request(`/billing/documents/${id}/excel`, { method: 'POST' })
  },
  searchBillingProducts(keyword = '') {
    return request('/billing/products', { query: { keyword } })
  },
  getAgentOutputs() {
    return agentRequest('/agent/outputs')
  },
  getAgentWorkbook(outputId) {
    return agentRequest(`/agent/outputs/${outputId}/workbook`)
  },
  saveAgentWorkbookVersion(outputId, data) {
    return agentRequest(`/agent/outputs/${outputId}/workbook-versions`, { method: 'POST', data })
  },
  getFileCenter(params = {}) {
    return request('/file-center', { query: params })
  },
  getFileCenterDetail(kind, id) {
    return request(`/file-center/${String(kind || '').toLowerCase()}/${id}`)
  },
  organizeFile(kind, id, data) {
    return request(`/file-center/${String(kind || '').toLowerCase()}/${id}`, { method: 'PUT', data })
  },
  getFinanceOverview(params = {}) {
    return request('/finance', { query: params })
  },
  createAccountDocument(data) {
    return request('/finance/documents', { method: 'POST', data })
  },
  getAccountDocument(id) {
    return request(`/finance/documents/${id}`)
  },
  recordAccountPayment(id, data) {
    return request(`/finance/documents/${id}/payments`, { method: 'POST', data })
  },
  recordCounterpartyPayment(data) {
    return request('/finance/payments', { method: 'POST', data })
  },
  getAccountSyncPreview() {
    return request('/finance/sync-preview')
  },
  syncOrderAccounts() {
    return request('/finance/sync-orders', { method: 'POST' })
  },
  exportFinanceStatement(data) {
    return request('/finance/statements', { method: 'POST', data })
  },
  getAgentRunOutputs(runId) {
    return agentRequest(`/agent/runs/${runId}/outputs`)
  },
  downloadAgentOutput(outputId) {
    return agentRequest(`/agent/outputs/${outputId}/download`, { responseType: 'arraybuffer' })
  },
  deleteAgentOutput(outputId) {
    // Some CloudBase gateway paths reject DELETE before reaching Spring MVC, so the POST action endpoint comes first.
    return agentRequest(`/agent/outputs/${outputId}/delete`, { method: 'POST' }).catch((postError) => {
      const message = String((postError && (postError.message || postError.errMsg)) || '')
      if (!/No static resource|not found|404/i.test(message)) return Promise.reject(postError)
      return agentRequest(`/agent/outputs/${outputId}`, { method: 'DELETE' })
    })
  },
  uploadExcelTask(file, purpose, context) {
    return uploadExcelFile(file, purpose, context)
  },
  getExcelTasks(status) {
    return request('/excel-tasks', { query: status ? { status } : {} })
  },
  getExcelTask(taskId) {
    return request(`/excel-tasks/${taskId}`)
  },
  getExcelTaskOcrResult(taskId) {
    return request(`/excel-tasks/${taskId}/ocr-result`)
  },
  deleteExcelTask(taskId) {
    // Use an action endpoint because some CloudBase gateway paths reject DELETE
    // before the request reaches Spring MVC.
    return request(`/excel-tasks/${taskId}/delete`, { method: 'POST' }).catch((postError) => {
      const postMessage = String((postError && (postError.message || postError.errMsg)) || '')
      if (!/No static resource|not found|404/i.test(postMessage)) return Promise.reject(postError)
      return request(`/excel-tasks/${taskId}`, { method: 'DELETE' }).catch((deleteError) => {
        const deleteMessage = String((deleteError && (deleteError.message || deleteError.errMsg)) || '')
        if (/not supported|method not allowed|405/i.test(deleteMessage)) {
          return Promise.reject({
            ...deleteError,
            message: '云端服务版本过旧，暂时不能删除。请先部署最新后端服务。'
          })
        }
        return Promise.reject(deleteError)
      })
    })
  },
  rewindExcelTaskToMapping(taskId, expectedVersion) {
    return request(`/excel-tasks/${taskId}/rewind-to-mapping`, { method: 'POST', data: { expectedVersion } })
  },
  getExcelTaskRows(taskId, sheetId, page = 0, size = 50) {
    return request(`/excel-tasks/${taskId}/sheets/${sheetId}/rows`, { query: { page, size } })
  },
  applyExcelMapping(taskId, sheetId, data) {
    return request(`/excel-tasks/${taskId}/sheets/${sheetId}/mapping`, { method: 'PUT', data })
  },
  skipExcelTaskSheet(taskId, sheetId, expectedVersion) {
    return request(`/excel-tasks/${taskId}/sheets/${sheetId}/skip`, { method: 'POST', data: { expectedVersion } })
  },
  getExcelReviewSummary(taskId, sheetId) {
    return request(`/excel-tasks/${taskId}/sheets/${sheetId}/review-summary`)
  },
  getExcelReviewRows(taskId, sheetId, page = 0, size = 50) {
    return request(`/excel-tasks/${taskId}/sheets/${sheetId}/review-rows`, { query: { page, size } })
  },
  reviewExcelRow(taskId, sheetId, resultId, data) {
    return request(`/excel-tasks/${taskId}/sheets/${sheetId}/review-rows/${resultId}`, { method: 'PUT', data })
  },
  validateExcelTask(taskId, expectedVersion) {
    return request(`/excel-tasks/${taskId}/validate`, { method: 'POST', data: { expectedVersion } })
  },
  commitExcelTask(taskId, expectedVersion, idempotencyKey) {
    return request(`/excel-tasks/${taskId}/commit`, { method: 'POST', data: { expectedVersion, idempotencyKey } })
  },
  generateExcelQuote(taskId, data) {
    return request(`/excel-tasks/${taskId}/generate-quote`, { method: 'POST', data })
  },
  generateExcelWorkbook(taskId, data) {
    return request(`/excel-tasks/${taskId}/generate-workbook`, { method: 'POST', data })
  },
  getExcelOutputs(taskId) {
    return request(`/excel-tasks/${taskId}/outputs`)
  },
  downloadExcelOutput(taskId, outputId) {
    return request(`/excel-tasks/${taskId}/outputs/${outputId}/download`, { responseType: 'arraybuffer' })
  },
  createExcelComparison(data) {
    return request('/excel-comparisons', { method: 'POST', data })
  },
  getExcelComparison(comparisonId) {
    return request(`/excel-comparisons/${comparisonId}`)
  },
  getExcelComparisonItems(comparisonId, changeType, page = 0, size = 50) {
    return request(`/excel-comparisons/${comparisonId}/items`, { query: { ...(changeType ? { changeType } : {}), page, size } })
  },
  cancelExcelComparison(comparisonId) {
    return request(`/excel-comparisons/${comparisonId}/cancel`, { method: 'POST' })
  },
  getProductPriceHistory(productId, page = 0, size = 20) {
    return request(`/products/${productId}/price-history`, { query: { page, size } })
  },
  getSupplierPriceHistory(supplierId, productId, page = 0, size = 20) {
    return request(`/suppliers/${supplierId}/price-history`, { query: { ...(productId ? { productId } : {}), page, size } })
  },
  getProducts(params = {}) {
    return (() => request('/products', { data: params }))()
  },
  getCatalogSuppliers(keyword = '') { return request('/catalog/suppliers', { query: { keyword } }) },
  getCatalog(params = {}) {
    return (() => request('/catalog', { query: params }))()
  },
  getProduct(id) {
    return (() => request(`/products/${id}`))()
  },
  saveProduct(data) {
    return (() => request('/products', { method: 'POST', data }))()
  },
  updateProduct(id, data) {
    return (() => request(`/products/${id}`, { method: 'PUT', data }))()
  },
  deleteProduct(id) {
    return (() => request(`/products/${id}`, { method: 'DELETE' }).then(() => ({ success: true })))()
  },
  changeProductStatus(id, status) {
    return (() => request(`/products/${id}/status`, { method: 'PATCH', query: { status } }))()
  },
  getSuppliers(params = {}) {
    return (() => request('/suppliers', { data: params }))()
  },
  getSupplier(id) {
    return (() => request(`/suppliers/${id}`))()
  },
  saveSupplier(data) {
    return (() => request('/suppliers', { method: 'POST', data }))()
  },
  updateSupplier(id, data) {
    return (() => request(`/suppliers/${id}`, { method: 'PUT', data }))()
  },
  getSupplierPurchaseOrders(id) {
    return (() => request(`/suppliers/${id}/purchase-orders`))()
  },
  getSupplierPriceTrends(id) {
    return (() => request(`/suppliers/${id}/price-trends`))()
  },
  getCustomers(params = {}) {
    return (() => request('/customers', { data: params }))()
  },
  getCustomer(id) {
    return (() => request(`/customers/${id}`))()
  },
  saveCustomer(data) {
    return (() => request('/customers', { method: 'POST', data }))()
  },
  updateCustomer(id, data) {
    return (() => request(`/customers/${id}`, { method: 'PUT', data }))()
  },
  getCustomerSalesOrders(id) {
    return (() => request(`/customers/${id}/sales-orders`))()
  },
  getCustomerAccounts(id) {
    return (() => request(`/customers/${id}/accounts`))()
  },
  getFactoryLedgers(params = {}) {
    return (() => request('/factory-ledgers', { data: params }))()
  },
  getFactoryLedger(id, month) {
    return (() => request(`/factory-ledgers/${id}`, { data: { month } }))()
  },
  createFactoryLedger(data) {
    return (() => request('/factory-ledgers', { method: 'POST', data }))()
  },
  addFactoryLedgerTransaction(id, data) {
    return (() => request(`/factory-ledgers/${id}/transactions`, { method: 'POST', data }))()
  },
  updateFactoryLedger(id, data) {
    return request(`/factory-ledgers/${id}`, { method: 'PUT', data })
  },
  reverseFactoryLedgerTransaction(id, transactionId, reason) {
    return request(`/factory-ledgers/${id}/transactions/${transactionId}/reverse`, { method: 'POST', data: { reason } })
  },
  reopenBillingDocument(id) {
    return request(`/billing/documents/${id}/reopen`, { method: 'POST' })
  },
  voidBillingDocument(id, reason) {
    return request(`/billing/documents/${id}/void`, { method: 'POST', data: { reason } })
  },
  setFactoryLedgerSettlementStatus(id, month, status) {
    return (() => request(`/factory-ledgers/${id}/settlements/${month}/status`, { method: 'PUT', data: { status } }))()
  },
  getPurchaseOrders(params = {}) {
    return (() => request('/purchase-orders', { data: params }))()
  },
  getPurchaseOrder(id) {
    return (() => request(`/purchase-orders/${id}`))()
  },
  savePurchaseOrder(data) {
    return (() => request('/purchase-orders', { method: 'POST', data }))()
  },
  updatePurchaseOrder(id, data) {
    return (() => request(`/purchase-orders/${id}`, { method: 'PUT', data }))()
  },
  stockInPurchaseOrder(id) {
    return (() => request(`/purchase-orders/${id}/stock-in`, { method: 'PATCH' }))()
  },
  getSalesOrders(params = {}) {
    return (() => request('/sales-orders', { data: params }))()
  },
  getSalesOrder(id) {
    return (() => request(`/sales-orders/${id}`))()
  },
  saveSalesOrder(data) {
    return (() => request('/sales-orders', { method: 'POST', data }))()
  },
  updateSalesOrder(id, data) {
    return (() => request(`/sales-orders/${id}`, { method: 'PUT', data }))()
  },
  stockOutSalesOrder(id) {
    return (() => request(`/sales-orders/${id}/stock-out`, { method: 'PATCH' }))()
  },
  registerSalesPayment(id, data) {
    return (() => request(`/sales-orders/${id}/payment`, { method: 'PATCH', data }))()
  },
  ensureInventory(productId) {
    return request('/inventories/ensure', { method: 'POST', query: { productId } })
  },
  getInventories(params = {}) {
    return (() => request('/inventories', { data: params }))()
  },
  getInventory(id) {
    return (() => request(`/inventories/${id}`))()
  },
  adjustInventory(id, data) {
    return (() => request(`/inventories/${id}/adjust`, { method: 'PATCH', data }))()
  },
  getInventoryLogs(id) {
    return (() => request(`/inventories/${id}/logs`))()
  },
  getInventoryWarnings() {
    return (() => request('/inventories/warnings'))()
  },
  getDailyReport() {
    return (() => request('/reports/daily'))()
  },
  getMonthlyReport(year, month) {
    return (() => request('/reports/monthly', { query: { year, month } }))()
  },
  getYearlyReport(year) {
    return (() => request('/reports/yearly', { query: { year } }))()
  },
  getPurchaseReport(params = {}) {
    return (() => request('/reports/purchases', { query: params }))()
  },
  getSalesReport(params = {}) {
    return (() => request('/reports/sales', { query: params }))()
  },
  getInventoryReport() {
    return (() => request('/reports/inventories'))()
  },
  getChartReport(params = {}) {
    return (() => request('/reports/charts', { query: params }))()
  },
  exportReport(type, params = {}) {
    return (() => request('/reports/export', { query: { type, ...params } }))()
  },
  getUsers() {
    return (() => request('/settings/users'))()
  },
  getUser(id) {
    return (() => request(`/settings/users/${id}`))()
  },
  getLogs(params = {}) {
    return (() => request('/settings/logs', { query: params }))()
  }
}
