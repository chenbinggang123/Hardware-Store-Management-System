const { test } = require('node:test')
const assert = require('node:assert/strict')
const { formatAgentContent } = require('../miniprogram/utils/agent-format')

test('inventory response preserves columns, empty units and surrounding headings', () => {
  const blocks = formatAgentContent('## 结论\n昨天确认采购单 1 张。\n\n**明细**\n| 商品 | 单位 | 入库 | 净增 |\n| :--- | --- | ---: | ---: |\n| 螺旋丝攻M6 | 支 | 26 | 26 |\n| M10 | | 50 | 50 |\n\n提示\n部分商品名称待核对。')
  const table = blocks.find(block => block.type === 'table')
  assert.deepEqual(table.columns.map(column => column.label), ['商品', '单位', '入库', '净增'])
  assert.equal(table.rows[1].cells[1].text, '')
  assert.equal(table.rows[0].cells[2].numeric, true)
  assert.equal(table.rows[0].cells[0].text, '螺旋丝攻M6')
  assert.deepEqual(blocks.filter(block => block.type === 'heading').map(block => block.text), ['结论', '明细', '提示'])
  assert.equal(blocks.at(-1).text, '部分商品名称待核对。')
  assert.ok(!blocks.some(block => block.type === 'list'))
})

test('multiple tables and partial streaming rows retain all content in sequence', () => {
  const table = '| 商品 | 数量 |\n| --- | --- |\n| A | 0 |'
  const blocks = formatAgentContent(`${table}\n\n中间说明\n\n${table}\n| 尚未输出完整`)
  assert.equal(blocks.filter(block => block.type === 'table').length, 2)
  assert.ok(blocks.some(block => block.text === '中间说明'))
  assert.ok(blocks.at(-1).text.includes('尚未输出完整'))
  assert.equal(blocks[0].rows[0].cells[1].text, '0')
})

test('normal lists and specification symbols retain their meaning', () => {
  const blocks = formatAgentContent('## 下一步\n- 核对 M6×20，¥3.50\n- 确认数量')
  assert.equal(blocks[0].type, 'heading')
  assert.equal(blocks[1].type, 'list')
  assert.equal(blocks[1].text, '核对 M6×20，¥3.50')
})
