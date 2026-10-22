function key(documentId) {
  const session = wx.getStorageSync('auth_session') || {}
  const owner = session.user && session.user.id
  return owner ? `billing_local_draft:${owner}:${documentId || 'new'}` : null
}

function read(storageKey) {
  if (!storageKey) return null
  try {
    const draft = wx.getStorageSync(storageKey)
    return draft && draft.schema === 1 && draft.form && Array.isArray(draft.lines) ? draft : null
  } catch (_) { return null }
}

function write(storageKey, draft) {
  if (!storageKey) return false
  try {
    wx.setStorageSync(storageKey, { ...draft, schema: 1, updatedAt: Date.now() })
    return true
  } catch (_) { return false }
}

function remove(storageKey) {
  if (storageKey) {
    try { wx.removeStorageSync(storageKey) } catch (_) {}
  }
}

module.exports = { key, read, write, remove }
