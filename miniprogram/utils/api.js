const seedState = require('./mock-data')
const cloudConfig = require('../config/cloud')

const AUTH_STORAGE_KEY = 'auth_session'
const state = clone(seedState)

function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

function nowString() {
  const now = new Date()
  const pad = (value) => `${value}`.padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`
}

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
    header
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

function withFallback(executor, fallbackResolver) {
  return executor().catch((error) => {
    if (cloudConfig.envId && cloudConfig.serviceName) {
      return Promise.reject(error)
    }
    if (!shouldFallback(error)) {
      return Promise.reject(error)
    }
    return Promise.resolve(typeof fallbackResolver === 'function' ? fallbackResolver() : fallbackResolver)
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

function cloudPathFor(fileName) {
  const session = getAuthSession()
  const operatorId = session && session.user && session.user.id ? session.user.id : 'unknown'
  const now = new Date()
  const extension = fileName.includes('.') ? `.${fileName.split('.').pop().toLowerCase()}` : ''
  const datePath = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('/')
  return `agent/${operatorId}/${datePath}/${Date.now()}-${Math.random().toString(36).slice(2, 10)}${extension}`
}

function removeCloudFile(fileID) {
  if (!fileID || !wx.cloud || !wx.cloud.deleteFile) return Promise.resolve()
  return wx.cloud.deleteFile({ fileList: [fileID] }).catch(() => null)
}

function uploadAgentFile(file, conversationId) {
  if (!wx.cloud || !wx.cloud.uploadFile || !wx.cloud.getTempFileURL) {
    return Promise.reject({ message: '当前微信基础库不支持 CloudBase 文件上传' })
  }
  const filePath = localFilePath(file)
  const originalName = localFileName(file)
  let uploadedFileID = ''
  return localFileSize(file, filePath)
    .then((fileSize) => wx.cloud.uploadFile({ cloudPath: cloudPathFor(originalName), filePath })
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
          data: { cloudFileId: uploadedFileID, downloadUrl: item.tempFileURL, originalName, fileSize, conversationId }
        })
      })
      .then((attachment) => ({ ...attachment, cloudFileId: uploadedFileID })))
    .catch((error) => removeCloudFile(uploadedFileID).then(() => Promise.reject(error)))
}

function nextId(list) {
  return list.reduce((max, item) => Math.max(max, Number(item.id) || 0), 0) + 1
}

function toNumber(value, fallback = 0) {
  const result = Number(value)
  return Number.isFinite(result) ? result : fallback
}

function containsText(source, keyword) {
  return `${source || ''}`.toLowerCase().includes(`${keyword || ''}`.trim().toLowerCase())
}

function findById(list, id) {
  return clone(list.find((item) => Number(item.id) === Number(id)) || null)
}

function productById(id) {
  return state.products.find((item) => Number(item.id) === Number(id))
}

function supplierById(id) {
  return state.suppliers.find((item) => Number(item.id) === Number(id))
}

function customerById(id) {
  return state.customers.find((item) => Number(item.id) === Number(id))
}

function inventoryById(id) {
  return state.inventories.find((item) => Number(item.id) === Number(id))
}

function inventoryByProductId(productId) {
  return state.inventories.find((item) => Number(item.productId) === Number(productId))
}

function addLog(module, action, detail, operatorId = 1) {
  state.logs.unshift({
    id: nextId(state.logs),
    operatorId,
    module,
    action,
    detail,
    createTime: nowString()
  })
}

function ensureInventory(product) {
  let inventory = inventoryByProductId(product.id)
  if (!inventory) {
    inventory = {
      id: nextId(state.inventories),
      productId: product.id,
      quantity: toNumber(product.stock),
      locationId: product.locationId || '',
      warningThreshold: 10,
      lastUpdateTime: nowString()
    }
    state.inventories.push(inventory)
  }
  return inventory
}

function syncProductFromInventory(inventory) {
  const product = productById(inventory.productId)
  if (product) {
    product.stock = toNumber(inventory.quantity)
    product.locationId = inventory.locationId
  }
}

function resolveSalesPrice(product, customer) {
  if (!product) {
    return 0
  }
  if (!customer || !customer.type) {
    return toNumber(product.retailPrice)
  }
  if (`${customer.type}`.toUpperCase() === 'B') {
    return toNumber(product.wholesalePrice)
  }
  if (customer.type === '老客户') {
    return toNumber(product.oldCustomerPrice)
  }
  return toNumber(product.retailPrice)
}

function recalculateCustomerDebt(customerId) {
  const customer = customerById(customerId)
  if (!customer) {
    return
  }
  customer.debt = Number(state.salesOrders
    .filter((item) => Number(item.customerId) === Number(customerId))
    .reduce((total, item) => total + toNumber(item.debtAmount), 0)
    .toFixed(2))
}

function normalizePurchaseItems(items) {
  return (items || []).map((item) => {
    const product = productById(item.productId)
    const price = toNumber(item.price, product ? product.costPrice : 0)
    const quantity = toNumber(item.quantity, 1)
    return {
      productId: toNumber(item.productId),
      productName: item.productName || (product ? product.name : ''),
      quantity,
      price,
      amount: Number((price * quantity).toFixed(2)),
      locationId: item.locationId || (product ? product.locationId : '')
    }
  })
}

function normalizeSalesItems(items, customerId) {
  const customer = customerById(customerId)
  return (items || []).map((item) => {
    const product = productById(item.productId)
    const price = toNumber(item.price, resolveSalesPrice(product, customer))
    const quantity = toNumber(item.quantity, 1)
    return {
      productId: toNumber(item.productId),
      productName: item.productName || (product ? product.name : ''),
      quantity,
      price,
      amount: Number((price * quantity).toFixed(2))
    }
  })
}

function summarizeAmount(items) {
  return Number(items.reduce((total, item) => total + toNumber(item.amount), 0).toFixed(2))
}

function normalizePurchaseOrderPayload(payload, existingOrder) {
  const items = normalizePurchaseItems(payload.items)
  return {
    id: existingOrder ? existingOrder.id : nextId(state.purchaseOrders),
    orderNumber: existingOrder ? existingOrder.orderNumber : `PO${Date.now()}`,
    supplierId: toNumber(payload.supplierId),
    operatorId: toNumber(payload.operatorId, 1),
    orderTime: payload.orderTime || (existingOrder ? existingOrder.orderTime : '') || nowString(),
    totalAmount: summarizeAmount(items),
    status: payload.status || (existingOrder ? existingOrder.status : '') || '待入库',
    remark: payload.remark || '',
    createTime: (existingOrder ? existingOrder.createTime : '') || nowString(),
    items
  }
}

function normalizeSalesOrderPayload(payload, existingOrder) {
  const items = normalizeSalesItems(payload.items, payload.customerId || (existingOrder ? existingOrder.customerId : ''))
  const totalAmount = summarizeAmount(items)
  const receivedAmount = toNumber(payload.receivedAmount, (existingOrder ? existingOrder.receivedAmount : 0) || 0)
  const debtAmount = Number(Math.max(totalAmount - receivedAmount, 0).toFixed(2))
  return {
    id: existingOrder ? existingOrder.id : nextId(state.salesOrders),
    orderNumber: existingOrder ? existingOrder.orderNumber : `SO${Date.now()}`,
    customerId: toNumber(payload.customerId),
    operatorId: toNumber(payload.operatorId, 1),
    orderTime: payload.orderTime || (existingOrder ? existingOrder.orderTime : '') || nowString(),
    totalAmount,
    status: payload.status || (existingOrder ? existingOrder.status : '') || '待出库',
    payStatus: debtAmount === 0 ? '已付' : receivedAmount > 0 ? '部分' : '未付',
    receivedAmount,
    debtAmount,
    createTime: (existingOrder ? existingOrder.createTime : '') || nowString(),
    items
  }
}

function computeSummary(label) {
  return {
    label,
    purchase: Number(state.purchaseOrders.reduce((sum, item) => sum + toNumber(item.totalAmount), 0).toFixed(2)),
    sales: Number(state.salesOrders.reduce((sum, item) => sum + toNumber(item.totalAmount), 0).toFixed(2)),
    inventoryWarnings: state.inventories.filter((item) => toNumber(item.quantity) <= toNumber(item.warningThreshold)).length
  }
}

function filterProducts(params = {}) {
  return clone(state.products.filter((item) => {
    const matchesKeyword = !params.keyword
      || containsText(item.name, params.keyword)
      || containsText(item.barcode, params.keyword)
      || containsText(item.sourceFactory, params.keyword)
    const matchesStatus = params.status === undefined || params.status === null || params.status === ''
      || Number(item.status) === Number(params.status)
    return matchesKeyword && matchesStatus
  }))
}

function filterSuppliers(params = {}) {
  return clone(state.suppliers.filter((item) => {
    return !params.keyword
      || containsText(item.name, params.keyword)
      || containsText(item.contact, params.keyword)
      || containsText(item.phone, params.keyword)
  }))
}

function filterCustomers(params = {}) {
  return clone(state.customers.filter((item) => {
    const matchesKeyword = !params.keyword || containsText(item.name, params.keyword) || containsText(item.phone, params.keyword)
    const matchesType = !params.type || `${item.type}` === `${params.type}`
    return matchesKeyword && matchesType
  }))
}

function filterPurchaseOrders(params = {}) {
  return clone(state.purchaseOrders.filter((item) => {
    return (!params.supplierId || Number(item.supplierId) === Number(params.supplierId))
      && (!params.status || item.status === params.status)
  }))
}

function filterSalesOrders(params = {}) {
  return clone(state.salesOrders.filter((item) => {
    return (!params.customerId || Number(item.customerId) === Number(params.customerId))
      && (!params.status || item.status === params.status)
      && (!params.payStatus || item.payStatus === params.payStatus)
  }))
}

function filterInventories(params = {}) {
  return clone(state.inventories.filter((item) => {
    const product = productById(item.productId)
    const matchesKeyword = !params.keyword
      || containsText(product ? product.name : '', params.keyword)
      || containsText(product ? product.barcode : '', params.keyword)
    const matchesWarning = !params.warningOnly || toNumber(item.quantity) <= toNumber(item.warningThreshold)
    return matchesKeyword && matchesWarning
  }))
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
  uploadAgentAttachment(filePath, conversationId) {
    return uploadAgentFile(filePath, conversationId)
  },
  deleteAgentAttachment(attachment) {
    const attachmentId = typeof attachment === 'object' ? attachment.id : attachment
    const cloudFileId = typeof attachment === 'object' ? attachment.cloudFileId : ''
    return agentRequest(`/agent/attachments/${attachmentId}`, { method: 'DELETE' })
      .then((result) => removeCloudFile(cloudFileId).then(() => result))
  },
  getAgentConversations() {
    return agentRequest('/agent/conversations')
  },
  getAgentConversation(conversationId) {
    return agentRequest(`/agent/conversations/${conversationId}`)
  },
  deleteAgentConversation(conversationId) {
    return agentRequest(`/agent/conversations/${conversationId}`, { method: 'DELETE' })
      .then((cloudFileIds) => Promise.all((cloudFileIds || []).map(removeCloudFile)))
  },
  getAgentRun(runId) {
    return agentRequest(`/agent/runs/${runId}`)
  },
  resolveAgentApproval(runId, approved) {
    return agentRequest(`/agent/runs/${runId}/approval`, { method: 'POST', data: { approved } })
  },
  getProducts(params = {}) {
    return withFallback(() => request('/products', { data: params }), () => filterProducts(params))
  },
  getProduct(id) {
    return withFallback(() => request(`/products/${id}`), () => findById(state.products, id))
  },
  saveProduct(data) {
    return withFallback(() => request('/products', { method: 'POST', data }), () => {
      const created = { id: nextId(state.products), createTime: nowString(), ...clone(data), status: Number(data.status || 1), stock: toNumber(data.stock) }
      state.products.unshift(created)
      ensureInventory(created)
      addLog('PRODUCT', 'CREATE', `新增商品：${created.name}`)
      return clone(created)
    })
  },
  updateProduct(id, data) {
    return withFallback(() => request(`/products/${id}`, { method: 'PUT', data }), () => {
      const product = productById(id)
      if (!product) return null
      Object.assign(product, clone(data), { id: Number(id), status: Number(data.status), stock: toNumber(data.stock) })
      const inventory = ensureInventory(product)
      inventory.quantity = toNumber(product.stock)
      inventory.locationId = product.locationId || inventory.locationId
      inventory.lastUpdateTime = nowString()
      addLog('PRODUCT', 'UPDATE', `更新商品：${product.name}`)
      return clone(product)
    })
  },
  deleteProduct(id) {
    return withFallback(() => request(`/products/${id}`, { method: 'DELETE' }).then(() => ({ success: true })), () => {
      const index = state.products.findIndex((item) => Number(item.id) === Number(id))
      if (index < 0) {
        return { success: false }
      }
      const product = state.products[index]
      state.products.splice(index, 1)

      const inventoryIndex = state.inventories.findIndex((item) => Number(item.productId) === Number(id))
      if (inventoryIndex >= 0) {
        state.inventories.splice(inventoryIndex, 1)
      }

      for (let logIndex = state.inventoryLogs.length - 1; logIndex >= 0; logIndex -= 1) {
        if (Number(state.inventoryLogs[logIndex].productId) === Number(id)) {
          state.inventoryLogs.splice(logIndex, 1)
        }
      }

      addLog('PRODUCT', 'DELETE', `删除商品：${product.name}`)
      return { success: true }
    })
  },
  changeProductStatus(id, status) {
    return withFallback(() => request(`/products/${id}/status`, { method: 'PATCH', query: { status } }), () => {
      const product = productById(id)
      if (!product) return null
      product.status = Number(status)
      addLog('PRODUCT', 'UPDATE_STATUS', `更新商品状态：${product.name}`)
      return clone(product)
    })
  },
  getSuppliers(params = {}) {
    return withFallback(() => request('/suppliers', { data: params }), () => filterSuppliers(params))
  },
  getSupplier(id) {
    return withFallback(() => request(`/suppliers/${id}`), () => findById(state.suppliers, id))
  },
  saveSupplier(data) {
    return withFallback(() => request('/suppliers', { method: 'POST', data }), () => {
      const created = { id: nextId(state.suppliers), createTime: nowString(), ...clone(data) }
      state.suppliers.unshift(created)
      addLog('SUPPLIER', 'CREATE', `新增供应商：${created.name}`)
      return clone(created)
    })
  },
  updateSupplier(id, data) {
    return withFallback(() => request(`/suppliers/${id}`, { method: 'PUT', data }), () => {
      const supplier = supplierById(id)
      if (!supplier) return null
      Object.assign(supplier, clone(data), { id: Number(id) })
      addLog('SUPPLIER', 'UPDATE', `更新供应商：${supplier.name}`)
      return clone(supplier)
    })
  },
  getSupplierPurchaseOrders(id) {
    return withFallback(() => request(`/suppliers/${id}/purchase-orders`), () => clone(state.purchaseOrders.filter((item) => Number(item.supplierId) === Number(id))))
  },
  getSupplierPriceTrends(id) {
    return withFallback(() => request(`/suppliers/${id}/price-trends`), () => clone(state.purchaseOrders
      .filter((item) => Number(item.supplierId) === Number(id))
      .flatMap((order) => (order.items || []).map((detail) => ({
        supplierId: Number(id),
        productId: detail.productId,
        productName: detail.productName,
        price: detail.price,
        orderNumber: order.orderNumber,
        orderTime: order.orderTime
      })))))
  },
  getCustomers(params = {}) {
    return withFallback(() => request('/customers', { data: params }), () => filterCustomers(params))
  },
  getCustomer(id) {
    return withFallback(() => request(`/customers/${id}`), () => findById(state.customers, id))
  },
  saveCustomer(data) {
    return withFallback(() => request('/customers', { method: 'POST', data }), () => {
      const created = { id: nextId(state.customers), createTime: nowString(), debt: toNumber(data.debt), ...clone(data) }
      state.customers.unshift(created)
      addLog('CUSTOMER', 'CREATE', `新增客户：${created.name}`)
      return clone(created)
    })
  },
  updateCustomer(id, data) {
    return withFallback(() => request(`/customers/${id}`, { method: 'PUT', data }), () => {
      const customer = customerById(id)
      if (!customer) return null
      Object.assign(customer, clone(data), { id: Number(id), debt: toNumber(data.debt, customer.debt) })
      addLog('CUSTOMER', 'UPDATE', `更新客户：${customer.name}`)
      return clone(customer)
    })
  },
  getCustomerSalesOrders(id) {
    return withFallback(() => request(`/customers/${id}/sales-orders`), () => clone(state.salesOrders.filter((item) => Number(item.customerId) === Number(id))))
  },
  getCustomerAccounts(id) {
    return withFallback(() => request(`/customers/${id}/accounts`), () => clone(state.salesOrders
      .filter((item) => Number(item.customerId) === Number(id) && toNumber(item.debtAmount) > 0)
      .map((item) => ({
        customerId: Number(id),
        orderId: item.id,
        orderNumber: item.orderNumber,
        totalAmount: item.totalAmount,
        receivedAmount: item.receivedAmount,
        debtAmount: item.debtAmount,
        status: item.payStatus,
        createTime: item.createTime
      }))))
  },
  getPurchaseOrders(params = {}) {
    return withFallback(() => request('/purchase-orders', { data: params }), () => filterPurchaseOrders(params))
  },
  getPurchaseOrder(id) {
    return withFallback(() => request(`/purchase-orders/${id}`), () => findById(state.purchaseOrders, id))
  },
  savePurchaseOrder(data) {
    return withFallback(() => request('/purchase-orders', { method: 'POST', data }), () => {
      const created = normalizePurchaseOrderPayload(data)
      state.purchaseOrders.unshift(created)
      addLog('PURCHASE', 'CREATE', `新建采购单：${created.orderNumber}`, created.operatorId)
      return clone(created)
    })
  },
  updatePurchaseOrder(id, data) {
    return withFallback(() => request(`/purchase-orders/${id}`, { method: 'PUT', data }), () => {
      const index = state.purchaseOrders.findIndex((item) => Number(item.id) === Number(id))
      if (index < 0) return null
      const saved = normalizePurchaseOrderPayload(data, state.purchaseOrders[index])
      state.purchaseOrders.splice(index, 1, saved)
      addLog('PURCHASE', 'UPDATE', `更新采购单：${saved.orderNumber}`, saved.operatorId)
      return clone(saved)
    })
  },
  stockInPurchaseOrder(id) {
    return withFallback(() => request(`/purchase-orders/${id}/stock-in`, { method: 'PATCH' }), () => {
      const order = state.purchaseOrders.find((item) => Number(item.id) === Number(id))
      if (!order || order.status === '已入库') return clone(order)
      ;(order.items || []).forEach((detail) => {
        const product = productById(detail.productId)
        if (!product) return
        const inventory = ensureInventory(product)
        const beforeQuantity = toNumber(inventory.quantity)
        const afterQuantity = beforeQuantity + toNumber(detail.quantity)
        inventory.quantity = afterQuantity
        inventory.locationId = detail.locationId || inventory.locationId
        inventory.lastUpdateTime = nowString()
        syncProductFromInventory(inventory)
        state.inventoryLogs.unshift({
          id: nextId(state.inventoryLogs),
          productId: product.id,
          productName: product.name,
          changeType: '入库',
          quantity: toNumber(detail.quantity),
          beforeQuantity,
          afterQuantity,
          operatorId: order.operatorId,
          relatedOrderId: order.id,
          remark: `采购单入库：${order.orderNumber}`,
          createTime: nowString()
        })
      })
      order.status = '已入库'
      addLog('PURCHASE', 'STOCK_IN', `采购单入库：${order.orderNumber}`, order.operatorId)
      return clone(order)
    })
  },
  getSalesOrders(params = {}) {
    return withFallback(() => request('/sales-orders', { data: params }), () => filterSalesOrders(params))
  },
  getSalesOrder(id) {
    return withFallback(() => request(`/sales-orders/${id}`), () => findById(state.salesOrders, id))
  },
  saveSalesOrder(data) {
    return withFallback(() => request('/sales-orders', { method: 'POST', data }), () => {
      const created = normalizeSalesOrderPayload(data)
      state.salesOrders.unshift(created)
      recalculateCustomerDebt(created.customerId)
      addLog('SALES', 'CREATE', `新建销售单：${created.orderNumber}`, created.operatorId)
      return clone(created)
    })
  },
  updateSalesOrder(id, data) {
    return withFallback(() => request(`/sales-orders/${id}`, { method: 'PUT', data }), () => {
      const index = state.salesOrders.findIndex((item) => Number(item.id) === Number(id))
      if (index < 0) return null
      const saved = normalizeSalesOrderPayload(data, state.salesOrders[index])
      state.salesOrders.splice(index, 1, saved)
      recalculateCustomerDebt(saved.customerId)
      addLog('SALES', 'UPDATE', `更新销售单：${saved.orderNumber}`, saved.operatorId)
      return clone(saved)
    })
  },
  stockOutSalesOrder(id) {
    return withFallback(() => request(`/sales-orders/${id}/stock-out`, { method: 'PATCH' }), () => {
      const order = state.salesOrders.find((item) => Number(item.id) === Number(id))
      if (!order || order.status === '已出库') return clone(order)
      ;(order.items || []).forEach((detail) => {
        const product = productById(detail.productId)
        if (!product) return
        const inventory = ensureInventory(product)
        const beforeQuantity = toNumber(inventory.quantity)
        const afterQuantity = Math.max(beforeQuantity - toNumber(detail.quantity), 0)
        inventory.quantity = afterQuantity
        inventory.lastUpdateTime = nowString()
        syncProductFromInventory(inventory)
        state.inventoryLogs.unshift({
          id: nextId(state.inventoryLogs),
          productId: product.id,
          productName: product.name,
          changeType: '出库',
          quantity: toNumber(detail.quantity),
          beforeQuantity,
          afterQuantity,
          operatorId: order.operatorId,
          relatedOrderId: order.id,
          remark: `销售单出库：${order.orderNumber}`,
          createTime: nowString()
        })
      })
      order.status = '已出库'
      addLog('SALES', 'STOCK_OUT', `销售单出库：${order.orderNumber}`, order.operatorId)
      return clone(order)
    })
  },
  registerSalesPayment(id, data) {
    return withFallback(() => request(`/sales-orders/${id}/payment`, { method: 'PATCH', data }), () => {
      const order = state.salesOrders.find((item) => Number(item.id) === Number(id))
      if (!order) return null
      const newReceived = Math.min(toNumber(order.totalAmount), toNumber(order.receivedAmount) + toNumber(data.receivedAmount))
      order.receivedAmount = Number(newReceived.toFixed(2))
      order.debtAmount = Number(Math.max(toNumber(order.totalAmount) - newReceived, 0).toFixed(2))
      order.payStatus = order.debtAmount === 0 ? '已付' : order.receivedAmount > 0 ? '部分' : '未付'
      recalculateCustomerDebt(order.customerId)
      addLog('SALES', 'PAYMENT', `销售单收款：${order.orderNumber}`, order.operatorId)
      return clone(order)
    })
  },
  getInventories(params = {}) {
    return withFallback(() => request('/inventories', { data: params }), () => filterInventories(params))
  },
  getInventory(id) {
    return withFallback(() => request(`/inventories/${id}`), () => findById(state.inventories, id))
  },
  adjustInventory(id, data) {
    return withFallback(() => request(`/inventories/${id}/adjust`, { method: 'PATCH', data }), () => {
      const inventory = inventoryById(id)
      if (!inventory) return null
      const beforeQuantity = toNumber(inventory.quantity)
      const afterQuantity = toNumber(data.actualQuantity)
      inventory.quantity = afterQuantity
      inventory.lastUpdateTime = nowString()
      syncProductFromInventory(inventory)
      const product = productById(inventory.productId)
      state.inventoryLogs.unshift({
        id: nextId(state.inventoryLogs),
        productId: inventory.productId,
        productName: product ? product.name : '',
        changeType: '盘点',
        quantity: afterQuantity - beforeQuantity,
        beforeQuantity,
        afterQuantity,
        operatorId: toNumber(data.operatorId, 1),
        relatedOrderId: inventory.id,
        remark: data.reason || '盘点调整',
        createTime: nowString()
      })
      addLog('INVENTORY', 'ADJUST', `盘点调整库存，商品ID：${inventory.productId}`, toNumber(data.operatorId, 1))
      return clone(inventory)
    })
  },
  getInventoryLogs(id) {
    return withFallback(() => request(`/inventories/${id}/logs`), () => {
      const inventory = inventoryById(id)
      if (!inventory) return []
      return clone(state.inventoryLogs.filter((item) => Number(item.productId) === Number(inventory.productId)))
    })
  },
  getInventoryWarnings() {
    return withFallback(() => request('/inventories/warnings'), () => clone(state.inventories.filter((item) => toNumber(item.quantity) <= toNumber(item.warningThreshold))))
  },
  getDailyReport() {
    return withFallback(() => request('/reports/daily'), () => computeSummary('日报'))
  },
  getMonthlyReport(year, month) {
    return withFallback(() => request('/reports/monthly', { query: { year, month } }), () => computeSummary('月报'))
  },
  getYearlyReport(year) {
    return withFallback(() => request('/reports/yearly', { query: { year } }), () => computeSummary('年报'))
  },
  getPurchaseReport(params = {}) {
    return withFallback(() => request('/reports/purchases', { query: params }), () => ({ count: state.purchaseOrders.length, totalAmount: Number(state.purchaseOrders.reduce((sum, item) => sum + toNumber(item.totalAmount), 0).toFixed(2)), orders: clone(state.purchaseOrders) }))
  },
  getSalesReport(params = {}) {
    return withFallback(() => request('/reports/sales', { query: params }), () => ({ count: state.salesOrders.length, totalAmount: Number(state.salesOrders.reduce((sum, item) => sum + toNumber(item.totalAmount), 0).toFixed(2)), receivedAmount: Number(state.salesOrders.reduce((sum, item) => sum + toNumber(item.receivedAmount), 0).toFixed(2)), orders: clone(state.salesOrders) }))
  },
  getInventoryReport() {
    return withFallback(() => request('/reports/inventories'), () => ({ totalInventoryRecords: state.inventories.length, warningCount: state.inventories.filter((item) => toNumber(item.quantity) <= toNumber(item.warningThreshold)).length, inventoryItems: clone(state.inventories) }))
  },
  getChartReport(params = {}) {
    return withFallback(() => request('/reports/charts', { query: params }), () => ({ sales: { orders: clone(state.salesOrders) }, purchases: { orders: clone(state.purchaseOrders) }, inventories: { inventoryItems: clone(state.inventories) } }))
  },
  exportReport(type, params = {}) {
    return withFallback(() => request('/reports/export', { query: { type, ...params } }), () => ({ type, startDate: params.startDate || '', endDate: params.endDate || '', generatedAt: nowString(), data: '当前为小程序原型态，导出结果以文本摘要展示' }))
  },
  getUsers() {
    return withFallback(() => request('/settings/users'), () => clone(state.users))
  },
  getUser(id) {
    return withFallback(() => request(`/settings/users/${id}`), () => findById(state.users, id))
  },
  getLogs(params = {}) {
    return withFallback(() => request('/settings/logs', { query: params }), () => clone(state.logs.filter((item) => (!params.module || item.module === params.module) && (!params.operatorId || Number(item.operatorId) === Number(params.operatorId)))))
  },
  backupSystem() {
    return withFallback(() => request('/settings/backup', { method: 'POST' }), () => {
      const result = { status: 'SUCCESS', backupName: `backup-${Date.now()}`, message: '开发态备份已生成模拟结果' }
      addLog('SETTINGS', 'BACKUP', '执行系统备份')
      return result
    })
  },
  restoreSystem(backupName) {
    return withFallback(() => request('/settings/restore', { method: 'POST', query: { backupName } }), () => {
      addLog('SETTINGS', 'RESTORE', `执行系统恢复：${backupName}`)
      return { status: 'SUCCESS', backupName, message: '开发态恢复已返回模拟结果' }
    })
  }
}
