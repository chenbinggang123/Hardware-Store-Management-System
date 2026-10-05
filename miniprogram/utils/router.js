const TAB_PAGES = new Set([
  '/pages/workbench/index',
  '/pages/products/index',
  '/pages/sales-orders/index',
  '/pages/inventories/index',
  '/pages/agent/index'
])

function buildQuery(params) {
  return Object.keys(params || {})
    .filter((key) => params[key] !== undefined && params[key] !== null && params[key] !== '')
    .map((key) => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
    .join('&')
}

function normalizePath(path) {
  if (!path) {
    return ''
  }
  return path.startsWith('/') ? path : `/${path}`
}

function showToast(title, icon = 'none') {
  wx.showToast({
    title,
    icon,
    duration: 1800
  })
}

function openPage(path, params = {}, options = {}) {
  const normalizedPath = normalizePath(path)
  if (!normalizedPath) {
    showToast('页面地址无效')
    return
  }

  if (TAB_PAGES.has(normalizedPath)) {
    wx.switchTab({
      url: normalizedPath,
      fail() {
        showToast('页面打开失败，请重试')
      }
    })
    return
  }

  const query = buildQuery(params)
  const url = query ? `${normalizedPath}?${query}` : normalizedPath
  const shouldReplace = options.replace || getCurrentPages().length >= 10

  const openWithRedirect = () => {
    wx.redirectTo({
      url,
      fail() {
        showToast('页面打开失败，请返回后重试')
      }
    })
  }

  if (shouldReplace) {
    openWithRedirect()
    return
  }

  wx.navigateTo({
    url,
    fail() {
      openWithRedirect()
    }
  })
}

module.exports = {
  openPage,
  showToast
}
