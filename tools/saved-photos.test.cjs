const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

function setup(source, mocks) {
  let page
  const sandbox = { Page(value) { page = value }, require(key) { return mocks[key] || {} }, wx: mocks.wx }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram', source), 'utf8'), sandbox)
  page.setData = function (data, callback) { Object.assign(this.data, data); if (callback) callback() }
  return page
}

function apiSetup(deleted) {
  const requests = []
  const removed = []
  const module = { exports: {} }
  const wx = { getStorageSync() {}, request(request) { requests.push(request); request.success({ statusCode: 200, data: { success: true, data: deleted } }) } }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/utils/api.js'), 'utf8'), {
    module, wx, require: () => ({ baseURL: 'https://hardware.plain-cookie.com', apiPrefix: '/api' })
  })
  return { api: module.exports, requests, removed }
}

test('removing a saved attachment is retained by the server without client storage deletion', async () => {
  const state = apiSetup(false)
  await state.api.deleteAgentAttachment({ id: 1, cloudFileId: 'cloud://saved' })
  assert.equal(state.requests[0].method, 'DELETE')
  assert.equal(state.removed.length, 0)
})

test('removing a temporary attachment delegates deletion to the server', async () => {
  const state = apiSetup(true)
  await state.api.deleteAgentAttachment({ id: 1, cloudFileId: 'cloud://temporary' })
  assert.equal(state.removed.length, 0)
  assert.equal(state.requests[0].url, 'https://hardware.plain-cookie.com/api/agent/attachments/1')
})

test('saved photo opens original directly, including when OCR failed', async () => {
  let preview
  const page = setup('pages/agent-files/index.js', {
    '../../utils/api': { getSavedPhotoSource: async () => 'agent/7/original.jpg', downloadSavedPhoto: async () => new ArrayBuffer(3), getFileCenter: async () => ({ items: [
      { key: 'attachment-1', id: 1, kind: 'ATTACHMENT', mimeType: 'image/jpeg', state: 'FAILED' }
    ] }) },
    wx: { env: { USER_DATA_PATH: '/data' }, getFileSystemManager: () => ({ writeFile(options) { options.success() } }), previewImage(value) { preview = value; return Promise.resolve() } }
  })
  await page.loadFiles()
  assert.equal(page.data.items[0].openText, '查看照片')
  await page.openItem({ currentTarget: { dataset: { key: 'attachment-1' } } })
  assert.equal(preview.current, '/data/saved-photo-1.jpg')
  assert.equal(page.data.openingKey, '')
})

test('photo failure releases loading state and allows retry', async () => {
  let toast
  const page = setup('pages/agent-files/index.js', {
    '../../utils/api': { getSavedPhotoSource: async () => { throw new Error('照片读取失败') } },
    '../../utils/router': { showToast(message) { toast = message } }, wx: {}
  })
  page.data.items = [{ key: 'attachment-1', id: 1, kind: 'ATTACHMENT', fileType: 'image' }]
  await page.openItem({ currentTarget: { dataset: { key: 'attachment-1' } } })
  assert.equal(toast, '照片读取失败')
  assert.equal(page.data.openingKey, '')
})
