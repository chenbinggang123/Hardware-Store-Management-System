const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path')
function setup() {
  let page
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../miniprogram/pages/billing-editor/index.js'),'utf8'), {
    Page(value) { page = value }, require() { return {} }, wx: {}, setTimeout, clearTimeout
  })
  page.setData = function (value) { Object.assign(this.data,value) }
  page.updateDraft = function (value,callback) { this.setData(value); if(callback)callback() }
  page.data.lines = [{name:'镀钛钻头',specification:'中5、8',unit:'支',quantity:'100',price:'4.2',rawName:'中5、8',rawSpecification:'',knowledgeAdvice:[{
    ruleId:'drill-diameter',productName:'镀钛钻头',originalSpecification:'中5、8',suggestedSpecification:'Φ5.8',title:'钻头直径',reason:'可能是多个尺寸，不改原文',sources:[{title:'参考目录',url:'https://example.com'}]
  }]}]
  page.recalculate()
  return page
}
test('draft keeps original fields and flags encyclopedia suggestion for explicit review', () => {
  const page = setup()
  assert.equal(page.data.lines[0].specification,'中5、8')
  assert.equal(page.data.lines[0].specificationUncertain,true)
  assert.equal(page.data.reviewLineCount,1)
  const payload = page.payload().lines[0]
  assert.equal(payload.rawName,'中5、8')
  assert.equal(payload.knowledgeAdvice[0].sources[0].url,'https://example.com')
  assert.equal(payload.knowledgeCards,undefined)
})
test('applying suggestion only changes specification and keeps evidence and trading values', () => {
  const page = setup()
  page.applyKnowledgeSuggestion({currentTarget:{dataset:{index:0,card:0}}})
  assert.equal(page.data.lines[0].specification,'Φ5.8')
  assert.equal(page.data.lines[0].rawName,'中5、8')
  assert.equal(page.data.lines[0].quantity,'100')
  assert.equal(page.data.lines[0].price,'4.2')
  assert.equal(page.data.lines[0].specificationUncertain,false)
  assert.equal(page.data.dirty,true)
})
test('keeping current specification records review without accepting candidate', () => {
  const page = setup()
  page.keepKnowledgeSpecification({currentTarget:{dataset:{index:0}}})
  assert.equal(page.data.lines[0].specification,'中5、8')
  assert.equal(page.data.lines[0].specificationUncertain,false)
  assert.ok(page.payload().lines[0].knowledgeReviewValue)
})
test('stale suggestions cannot apply after name/specification edits or confirmation', () => {
  const page = setup()
  page.data.lines[0].name='砂纸'
  page.applyKnowledgeSuggestion({currentTarget:{dataset:{index:0,card:0}}})
  assert.equal(page.data.lines[0].specification,'中5、8')
  page.data.lines[0].name='镀钛钻头'; page.data.document={status:'CONFIRMED'}
  page.applyKnowledgeSuggestion({currentTarget:{dataset:{index:0,card:0}}})
  assert.equal(page.data.lines[0].specification,'中5、8')
})
