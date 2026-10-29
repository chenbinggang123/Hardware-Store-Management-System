const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.join(__dirname, '../miniprogram')
function page(name, api) {
  let definition
  const routes = []
  const sandbox = { Page(value) { definition = value }, require(key) {
    if (key === '../../utils/api') return api
    if (key === '../../utils/router') return { openPage: (...args) => routes.push(args), showToast() {} }
    return {}
  }, wx: { redirectTo() {}, showToast() {} }, setTimeout, clearTimeout }
  vm.runInNewContext(fs.readFileSync(path.join(root, 'pages', name, 'index.js'), 'utf8'), sandbox)
  definition.data = structuredClone(definition.data)
  definition.setData = function (values, callback) {
    for (const [key, value] of Object.entries(values)) {
      const keys = key.split('.'); let target = this.data
      for (const field of keys.slice(0, -1)) target = target[field]
      target[keys.at(-1)] = value
    }
    if (callback) callback()
  }
  return { page: definition, routes }
}
test('catalog defaults to all products, supports supplier ledgers and uses the unified editor', async () => {
  let params
  const state = page('products', { getCatalog: async (value) => { params = value; return { items: [] } }, getCatalogSuppliers: async () => [{ factory: '源头厂A', productCount: 1 }] })
  state.page.onLoad({ factory: '源头厂A' }); await state.page.loadProducts(false)
  assert.equal(params.factory, '源头厂A'); assert.equal(params.view, 'all')
  state.page.openProcurement()
  assert.equal(state.routes[0][0], '/pages/billing-editor/index')
  assert.equal(state.routes[0][1].documentType, 'PROCUREMENT')
  await state.page.loadFactories()
  assert.equal(state.page.data.factories[0].factory, '源头厂A')
  assert.equal(state.page.openSale, undefined)
  assert.ok(!fs.readFileSync(path.join(root, 'pages/products/index.wxml'), 'utf8').includes('bindtap="openSale"'))
})
test('manual product save prevents repeat submission and retains inputs on failure', async () => {
  let reject, calls = 0
  const state = page('product-form', { saveProduct: () => { calls++; return new Promise((_, fail) => { reject = fail }) } })
  state.page.data.form.name = '螺丝'; state.page.data.form.unit = '个'
  const saving = state.page.submit(); state.page.submit()
  assert.equal(calls, 1)
  reject(Error('断网')); await saving
  assert.equal(state.page.data.form.name, '螺丝')
  assert.equal(state.page.data.saveError, '断网')
  assert.equal(state.page.data.saving, false)
})
test('quantity correction accepts zero, rejects empty and fractional values, and preserves cancel', async () => {
  let result, sent, ensured = 0
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/product-quantity.js'), 'utf8'), {
    module, require: () => ({ ensureInventory: async () => { ensured++; return { id: 9 } }, adjustInventory: async (_, body) => { sent = body; return body } }),
    wx: { showModal: async () => result }
  })
  const modify = module.exports.modifyQuantity
  result = { confirm: false }; await modify({ id: 1, quantity: 7 }); assert.equal(ensured, 0)
  for (const content of ['', '1.5', '-1', 'abc']) { result = { confirm: true, content }; await assert.rejects(modify({ id: 1 }), /整数数量/) }
  result = { confirm: true, content: '0' }; await modify({ id: 1 }); assert.equal(sent.actualQuantity, 0)
})
test('all touched page handlers and template expressions compile', () => {
  for (const name of ['products', 'product-form', 'product-detail', 'billing-editor', 'billing-list', 'home', 'workbench']) {
    const js = fs.readFileSync(path.join(root, 'pages', name, 'index.js'), 'utf8')
    const xml = fs.readFileSync(path.join(root, 'pages', name, 'index.wxml'), 'utf8')
    new vm.Script(js)
    for (const match of xml.matchAll(/(?:bind|catch)(?::)?[\w-]+\s*=\s*["']([A-Za-z_$][\w$]*)["']/g)) assert.ok(new RegExp('\\b'+match[1]+'\\s*\\(').test(js), `${name}: ${match[1]}`)
    for (const match of xml.matchAll(/{{([\s\S]*?)}}/g)) new Function('data', 'with(data) { return (' + match[1] + ') }')
  }
})
