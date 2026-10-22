const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const path = require('node:path')
test('book sorts and filters documents, opens details and uses the unified editor', async () => {
  let page
  const opened = []
  let deleted = null
  const ledger = { transactions: [
    {id: 1, date: '2026-10-01', type: 'PURCHASE', items: []},
    {id: 2, date: '2026-10-03', type: 'RETURN', source: 'BILLING', documentId: 20, items: []},
    {id: 3, date: '2026-10-02', type: 'PAYMENT', items: []},
    {id: 4, date: '2026-10-04', type: 'PURCHASE', reversed: true},
    {id: 5, date: '2026-10-04', type: 'PURCHASE', isReversal: true}
  ] }
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../miniprogram/pages/factory-ledger-detail/index.js'), 'utf8'), {
    Page: value => {page = value},
    require: name => name.endsWith('/factory-ledger') ? {currentMonth: () => '2026-10'}
      : name.endsWith('/api') ? {getFactoryLedger: async () => ledger, deleteLedgerBillingDocument: async (book,id) => { deleted = id; ledger.transactions = ledger.transactions.filter(row => row.id !== id) }}
      : {openPage: (...args) => opened.push(args), showToast() {}},
    wx: {showModal: async () => ({confirm: true})}
  })
  page.setData = function(values, cb) { Object.assign(this.data, values); if(cb) cb() }
  page.onLoad({id: 9})
  await page.loadData()
  assert.deepEqual(Array.from(page.data.visibleDocuments, row => row.id), [2,3,1])
  page.filterDocuments({currentTarget:{dataset:{type:'RETURN'}}})
  assert.equal(page.data.visibleDocuments.length,1)
  page.toggleDocument({currentTarget:{dataset:{id:2}}})
  assert.equal(page.data.expandedId,2)
  page.openDocument({currentTarget:{dataset:{id:20}}})
  assert.equal(opened[0][1].id,20)
  page.addPurchase()
  assert.equal(opened[1][0],'/pages/billing-editor/index')
  assert.equal(opened[1][1].ledgerId,9)
  page.deleteDocument({currentTarget:{dataset:{id:2}}})
  await new Promise(setImmediate)
  assert.equal(deleted,2)
  assert.equal(page.data.visibleDocuments.length,0)
})
