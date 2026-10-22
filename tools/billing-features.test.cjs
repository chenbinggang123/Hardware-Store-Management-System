const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const miniRoot = path.join(__dirname, '../miniprogram')

function setup(pageName, overrides = {}, storage = new Map()) {
  if (!storage.has('auth_session')) storage.set('auth_session', { user: { id: 7 } })
  let modalChoice = true, modal, alert = false
  const wx = {
    getStorageSync: (key) => storage.get(key),
    setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
    removeStorageSync: (key) => storage.delete(key),
    showToast() {},
    showModal(options) { modal = options; return Promise.resolve({ confirm: modalChoice }) },
    enableAlertBeforeUnload() { alert = true },
    disableAlertBeforeUnload() { alert = false }
  }
  const api = { searchBillingProducts: async () => [], getFactoryLedgers: async () => ({ ledgers: [] }), getBillingLedgerPosting: async () => ({ posted: false }), ...overrides }
  let page
  const sandbox = { wx, Page: (value) => { page = value }, setTimeout, clearTimeout, console }
  const localModule = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(miniRoot, 'utils/billing-local-draft.js'), 'utf8'), { ...sandbox, module: localModule })
  vm.runInNewContext(fs.readFileSync(path.join(miniRoot, 'pages', pageName, 'index.js'), 'utf8'), {
    ...sandbox,
    require(name) {
      if (name === '../../utils/api') return api
      if (name === '../../utils/billing-local-draft') return localModule.exports
      return { openPage() {}, showToast() {} }
    }
  })
  page.data = structuredClone(page.data)
  page.setData = function (values, callback) {
    for (const [key, value] of Object.entries(values)) {
      const keys = key.split('.'); let target = this.data
      for (const field of keys.slice(0, -1)) target = target[field]
      target[keys.at(-1)] = value
    }
    if (callback) callback()
  }
  return { page, storage, wx, api, local: localModule.exports, choose(value) { modalChoice = value }, get modal() { return modal }, get alert() { return alert } }
}
const tick = () => new Promise(setImmediate)
test('posted documents cannot be deleted or reopened outside their ledger', () => {
  let deleted = false
  const list = setup('billing-list', { deleteBillingDocument: async () => { deleted = true } })
  list.page.data.items = [{ id: 9, status: 'CONFIRMED', ledgerManaged: true }]
  list.page.deleteDocument({ currentTarget: { dataset: { id: 9 } } })
  assert.equal(list.modal, undefined)
  assert.equal(deleted, false)
  const editor = setup('billing-editor')
  editor.page.data.document = { id: 9, status: 'CONFIRMED' }
  editor.page.data.ledgerPosting = { posted: true }
  editor.page.reopenDocument()
  editor.page.voidDocument()
  assert.equal(editor.modal, undefined)
})
const changeCustomer = (page, value) => page.changeForm({ currentTarget: { dataset: { field: 'customerName' } }, detail: { value } })

test('editing persists immediately, survives leaving and restores the original request key', async () => {
  const first = setup('billing-editor'); first.page.onLoad({})
  await tick()
  changeCustomer(first.page, '恢复客户')
  first.page.changeLine({ currentTarget: { dataset: { index: 0, field: 'name' } }, detail: { value: '螺丝' } })
  assert.equal(first.storage.get(first.local.key()).lines[0].name, '螺丝')
  assert.equal(first.alert, true)
  const requestKey = first.page.payload().requestKey
  first.page.onUnload()
  const next = setup('billing-editor', {}, first.storage); next.page.onLoad({})
  await tick()
  assert.equal(next.page.data.form.customerName, '恢复客户')
  assert.equal(next.page.data.lines[0].name, '螺丝')
  assert.equal(next.page.payload().requestKey, requestKey)
})

test('discarding local contents starts a clean document', async () => {
  const state = setup('billing-editor'); state.page.onLoad({}); await tick()
  changeCustomer(state.page, '丢弃客户')
  const next = setup('billing-editor', {}, state.storage); next.choose(false); next.page.onLoad({}); await tick()
  assert.equal(next.page.data.form.customerName, '')
  assert.equal(next.local.read(next.local.key()), null)
})

test('failed save retains local contents; successful save clears cache and leave alert', async () => {
  const state = setup('billing-editor', { createBillingDocument: async () => { throw Error('断网') } })
  state.page.onLoad({}); await tick(); changeCustomer(state.page, '待保存')
  await state.page.saveDraft()
  assert.equal(state.local.read(state.local.key()).form.customerName, '待保存')
  state.api.createBillingDocument = async (content) => ({ id: 9, version: 0, status: 'DRAFT', content })
  await state.page.saveDraft()
  assert.equal(state.local.read(state.local.key()), null)
  assert.equal(state.page.data.dirty, false)
  assert.equal(state.alert, false)
})

test('typing during save is retained instead of overwritten by the server response', async () => {
  let finish
  const state = setup('billing-editor', { createBillingDocument: (content) => new Promise((resolve) => { finish = () => resolve({ id: 9, version: 0, status: 'DRAFT', content }) }) })
  state.page.onLoad({}); await tick(); changeCustomer(state.page, '旧客户')
  const saving = state.page.saveDraft(); changeCustomer(state.page, '新客户'); finish(); await saving
  assert.equal(state.page.data.form.customerName, '新客户')
  assert.equal(state.page.data.documentId, 9)
  assert.equal(state.local.read(state.local.key(9)).form.customerName, '新客户')
  assert.equal(state.local.read(state.local.key()), null)
})

test('stale saved-document recovery becomes a new document', async () => {
  const state = setup('billing-editor', { getBillingDocument: async () => ({ id: 9, version: 2, status: 'CONFIRMED', content: {} }) })
  state.local.write(state.local.key(9), { form: { customerName: '本机内容' }, lines: [], document: { id: 9, version: 1, status: 'DRAFT' } })
  state.page.onLoad({ id: '9' }); await tick()
  assert.equal(state.page.data.documentId, null)
  assert.equal(state.page.data.form.customerName, '本机内容')
  assert.equal(state.modal.confirmText, '恢复为新单')
})

test('storage failure is visible and keeps the leave warning', async () => {
  const state = setup('billing-editor'); state.page.onLoad({}); await tick()
  state.wx.setStorageSync = () => { throw Error('空间不足') }
  changeCustomer(state.page, '未保存')
  assert.match(state.page.data.localSaveText, /暂存失败/)
  assert.equal(state.alert, true)
})

test('an existing draft can recover from local storage while the network is unavailable', async () => {
  const state = setup('billing-editor', { getBillingDocument: async () => { throw Error('断网') } })
  state.local.write(state.local.key(9), { form: { customerName: '离线恢复' }, lines: [], document: { id: 9, version: 1, status: 'DRAFT' } })
  state.page.onLoad({ id: '9' }); await tick()
  assert.equal(state.page.data.error, '')
  assert.equal(state.page.data.documentId, 9)
  assert.equal(state.page.data.form.customerName, '离线恢复')
})

test('filter requests are server-side and stale responses cannot replace the latest results', async () => {
  let oldResolve
  const requests = []
  const state = setup('billing-list', { getBillingRecords: (params) => {
    requests.push(params)
    return params.status === '' ? new Promise((resolve) => { oldResolve = resolve }) : Promise.resolve({ items: [{ id: 9, status: 'DRAFT' }], total: 1 })
  } })
  state.page.load(true)
  state.page.changeStatus({ currentTarget: { dataset: { status: 'DRAFT' } } }); await tick()
  oldResolve({ items: [{ id: 8, status: 'CONFIRMED' }], total: 1 }); await tick()
  assert.equal(state.page.data.items[0].id, 9)
  assert.equal(requests[1].status, 'DRAFT')
})

test('bulk cleanup ignores non-drafts, supports cancellation, then refreshes and removes local copies', async () => {
  let deleted
  const state = setup('billing-list', { deleteBillingDrafts: async (ids) => { deleted = ids; return { deleted: ids.length } }, getBillingRecords: async () => ({ items: [], total: 0 }) })
  state.page.data.items = [{ id: 9, status: 'DRAFT' }, { id: 10, status: 'CONFIRMED' }]
  state.page.toggleSelection({ currentTarget: { dataset: { id: 10 } } })
  assert.equal(state.page.data.selectedIds.length, 0)
  state.page.toggleSelection({ currentTarget: { dataset: { id: 9 } } })
  state.page.deleteSelectedDrafts(); state.modal.success({ confirm: false }); assert.equal(deleted, undefined)
  state.local.write(state.local.key(9), { form: {}, lines: [] })
  state.modal.success({ confirm: true }); await tick()
  assert.equal(deleted[0], 9)
  assert.equal(state.local.read(state.local.key(9)), null)
  assert.equal(state.page.data.items.length, 0)
  assert.equal(state.page.data.bulkDeleting, false)
})

test('a failed batch deletion keeps the selection for retry', async () => {
  const state = setup('billing-list', { deleteBillingDrafts: async () => { throw Error('已保存') } })
  state.page.data.selectedIds = [9]
  state.page.deleteSelectedDrafts(); state.modal.success({ confirm: true }); await tick()
  assert.equal(state.page.data.selectedIds[0], 9)
  assert.equal(state.page.data.bulkDeleting, false)
})
